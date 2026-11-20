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
    data class Ready(val pack: Pack, val node: KavachNode, val sync: LanSync, val offlineMapDatabase: File?) : PackState
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
            _state.value = try {
                val pack = PackLoader.load({ name -> assets.open("pack/$name").use { it.readBytes() } })
                val offlineMap = pack.offlineMap?.let { installOfflineMap(it.archiveSha256, it.databaseSha256, it.databaseBytes) }
                val node = KavachNode(pack, db.events())
                PackState.Ready(pack, node, LanSync(node), offlineMap)
            } catch (e: Exception) {
                Log.e("KavachApp", "Offline pack or map initialization failed", e)
                PackState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }, "pack-loader").start()
    }

    private fun installOfflineMap(archiveHash: String, databaseHash: String, expectedBytes: Long): File {
        val database = File(filesDir, "telangana-map-$databaseHash.sqlite")
        if (database.isFile && database.length() == expectedBytes && sha256(database) == databaseHash) {
            verifyOfflineMapDatabase(database)
            return database
        }
        val temp = File(filesDir, "telangana-map.tmp")
        temp.delete()
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        val databaseDigest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        GZIPInputStream(DigestInputStream(assets.open("maps/telangana-map.mapdata"), archiveDigest)).use { input ->
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
        verifyOfflineMapDatabase(database)
        return database
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
