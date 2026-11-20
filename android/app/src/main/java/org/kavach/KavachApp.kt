package org.kavach

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kavach.data.KavachDb
import org.kavach.pack.Pack
import org.kavach.pack.PackLoader
import org.kavach.service.KavachNode
import org.kavach.sync.LanSync
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Pack loading state. The real pack is ~16 MB, so it is verified on a background thread. */
sealed interface PackState {
    data object Loading : PackState
    data class Ready(
        val pack: Pack,
        val node: KavachNode,
        val sync: LanSync,
        val offlineMapDatabase: File?,
        val offlineMapError: String? = null,
        val offlineRouteDatabase: File? = null,
        val offlineRouteError: String? = null,
    ) : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Process-wide container. The OpenStreetMap pack ships inside the APK under assets/pack and is
 * verified on every start (signature and every file hash) before any screen can use it.
 */
class KavachApp : Application() {

    lateinit var db: KavachDb
        private set

    private val _state = MutableStateFlow<PackState>(PackState.Loading)
    val packState: StateFlow<PackState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, KavachDb::class.java, "kavach.db").build()
        Thread({
            try {
                val pack = PackLoader.load({ name -> assets.open("pack/$name").use { it.readBytes() } })
                val node = KavachNode(pack, db.events())
                val ready = PackState.Ready(pack, node, LanSync(node), null)
                _state.value = ready
                pack.offlineMap?.let { metadata ->
                    var current = ready
                    try {
                        val offlineMap = installOfflineMap(metadata.archiveSha256, metadata.databaseSha256, metadata.databaseBytes)
                        current = current.copy(offlineMapDatabase = offlineMap)
                    } catch (e: Exception) {
                        Log.e("KavachApp", "Offline Telangana map initialization failed", e)
                        current = current.copy(offlineMapError = e.message ?: e.javaClass.simpleName)
                    }
                    _state.value = current
                    if (metadata.routesArchiveSha256 != null && metadata.routesDatabaseSha256 != null &&
                        metadata.routesDatabaseBytes != null) {
                        try {
                            val routes = installCompressedDatabase(
                                "maps/telangana-routes.mapdata", "telangana-routes",
                                metadata.routesArchiveSha256, metadata.routesDatabaseSha256,
                                metadata.routesDatabaseBytes, ::verifyOfflineRouteDatabase,
                            )
                            current = current.copy(offlineRouteDatabase = routes)
                        } catch (e: Exception) {
                            Log.e("KavachApp", "Offline statewide route database initialization failed", e)
                            current = current.copy(offlineRouteError = e.message ?: e.javaClass.simpleName)
                        }
                        _state.value = current
                    }
                }
            } catch (e: Exception) {
                Log.e("KavachApp", "Offline pack initialization failed", e)
                _state.value = PackState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }, "pack-loader").start()
    }

    private fun installOfflineMap(archiveHash: String, databaseHash: String, expectedBytes: Long): File {
        return installCompressedDatabase(
            "maps/telangana-map.mapdata", "telangana-map", archiveHash, databaseHash, expectedBytes,
            ::verifyOfflineMapDatabase,
        )
    }

    private fun installCompressedDatabase(
        assetPath: String,
        filePrefix: String,
        archiveHash: String,
        databaseHash: String,
        expectedBytes: Long,
        verify: (File) -> Unit,
    ): File {
        val database = File(filesDir, "$filePrefix-$databaseHash.sqlite")
        if (database.isFile && database.length() == expectedBytes && sha256(database) == databaseHash) {
            verify(database)
            return database
        }
        val temp = File(filesDir, "$filePrefix.tmp")
        temp.delete()
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        val databaseDigest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        GZIPInputStream(DigestInputStream(assets.open(assetPath), archiveDigest)).use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    databaseDigest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    written += count
                }
                output.fd.sync()
            }
        }
        if (hex(archiveDigest.digest()) != archiveHash || hex(databaseDigest.digest()) != databaseHash || written != expectedBytes) {
            temp.delete()
            throw IllegalStateException("Offline Telangana map failed integrity verification")
        }
        database.delete()
        if (!temp.renameTo(database)) {
            temp.delete()
            throw IllegalStateException("Offline Telangana map could not be installed")
        }
        verify(database)
        return database
    }

    private fun verifyOfflineRouteDatabase(database: File) {
        SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { routes ->
            for ((table, minimum) in listOf("route_nodes" to 2, "route_edges" to 1, "route_crossings" to 1, "route_places" to 1)) {
                routes.rawQuery("SELECT 1 FROM $table LIMIT $minimum", null).use { cursor ->
                    var rows = 0
                    while (rows < minimum && cursor.moveToNext()) rows++
                    if (rows < minimum) throw IllegalStateException("Statewide route database is missing $table data")
                }
            }
            for (index in listOf("route_edges_from", "route_edges_to", "route_edges_crossing", "route_places_name")) {
                routes.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(index)).use { cursor ->
                    if (!cursor.moveToFirst()) throw IllegalStateException("Statewide route index $index is missing")
                }
            }
            routes.rawQuery("SELECT 1 FROM route_metadata WHERE key='source_sha256' LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IllegalStateException("Statewide route source metadata is missing")
            }
        }
    }

    private fun verifyOfflineMapDatabase(database: File) {
        SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { map ->
            for (table in listOf("roads", "waterways", "places", "boundaries", "spatial_cells")) {
                map.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { cursor ->
                    if (!cursor.moveToFirst()) throw IllegalStateException("Offline Telangana map is missing $table data")
                }
            }
            val spatialChecks = listOf(
                "SELECT r.id FROM spatial_cells i JOIN roads r ON r.id=i.item_id WHERE i.layer='road' AND i.min_zoom BETWEEN 6 AND 7 AND i.cell_x BETWEEN 780 AND 815 AND i.cell_y BETWEEN 160 AND 195 AND r.min_zoom<=7 LIMIT 1",
                "SELECT w.id FROM spatial_cells i JOIN waterways w ON w.id=i.item_id WHERE i.layer='water' AND i.min_zoom=0 AND i.cell_x BETWEEN 780 AND 815 AND i.cell_y BETWEEN 160 AND 195 LIMIT 1",
                "SELECT b.id FROM spatial_cells i JOIN boundaries b ON b.id=i.item_id WHERE i.layer='boundary' AND i.min_zoom BETWEEN 6 AND 7 AND i.cell_x BETWEEN 780 AND 815 AND i.cell_y BETWEEN 160 AND 195 AND b.min_zoom<=7 LIMIT 1",
            )
            for (query in spatialChecks) {
                map.rawQuery(query, null).use { cursor ->
                    if (!cursor.moveToFirst()) throw IllegalStateException("Offline Telangana map spatial query failed")
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
