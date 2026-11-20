package org.kavach.pack

import org.kavach.core.Crypto
import org.kavach.core.Json
import org.kavach.core.TrustEntry
import org.kavach.core.TrustRegistry
import org.kavach.graph.RoadGraph

class PackException(message: String) : Exception(message)

/** A water line for the map: river, stream, canal or drain (OpenStreetMap, ODbL). */
data class MapFeature(val kind: String, val name: String, val coords: List<Pair<Double, Double>>)
data class IndiaAdminArea(val name: String, val code: String, val rings: List<List<Pair<Double, Double>>>)
data class OfflineMapMetadata(val archiveSha256: String, val databaseSha256: String, val databaseBytes: Long)

data class Pack(
    val packVersion: String,
    val createdAt: String,
    val sourceClass: String,
    val graph: RoadGraph,
    val trust: TrustRegistry,
    val features: List<MapFeature>,
    val indiaAreas: List<IndiaAdminArea>,
    val attribution: String,
    val offlineMap: OfflineMapMetadata? = null,
)

/**
 * Verifies and loads a signed offline pack (dossier sections 34 and 43). The pack-signer key is
 * pinned here, not read from the pack, so a replaced pack cannot vouch for itself. Every file listed
 * in the manifest is hash-checked before anything is used.
 */
object PackLoader {

    private val PINNED_PACK_SIGNER: ByteArray by lazy {
        Crypto.publicKeyFromSeed(Crypto.demoSeed(Crypto.PACK_SIGNER_LABEL))
    }

    /** [read] returns the bytes of a named pack file, e.g. from assets or a directory. */
    fun load(read: (String) -> ByteArray, minCreatedAt: String? = null): Pack {
        val manifestBytes = try { read("manifest.json") } catch (e: Exception) {
            throw PackException("manifest unreadable: ${e.message}")
        }
        val manifest = asMap(Json.parse(String(manifestBytes, Charsets.UTF_8)))
        val sigDoc = asMap(Json.parse(String(read("signature.json"), Charsets.UTF_8)))
        val sig = Crypto.unhex(sigDoc["signature"] as String)
        if (!Crypto.verify(manifestBytes, sig, PINNED_PACK_SIGNER)) {
            throw PackException("manifest signature invalid")
        }

        val files = asMap(manifest["files"])
        val contents = HashMap<String, ByteArray>()
        for ((name, expected) in files) {
            val bytes = read(name)
            if (Crypto.sha256Hex(bytes) != expected) throw PackException("hash mismatch for $name")
            contents[name] = bytes
        }

        val createdAt = manifest["created_at"] as String
        if (minCreatedAt != null && createdAt < minCreatedAt) {
            throw PackException("downgrade rejected: pack older than installed pack")
        }

        val graphBytes = contents["graph.json"] ?: throw PackException("graph.json missing from manifest")
        val graphVersion = "g-" + Crypto.sha256Hex(graphBytes).take(12)
        val criticality = contents["criticality.json"]?.let { bytes ->
            asMap(Json.parse(String(bytes, Charsets.UTF_8))).mapValues { (it.value as Number).toInt() }
        }
        val graph = RoadGraph.fromJson(String(graphBytes, Charsets.UTF_8), graphVersion, criticality)

        val trustRaw = asMap(Json.parse(String(contents.getValue("trust.json"), Charsets.UTF_8)))
        @Suppress("UNCHECKED_CAST")
        val entries = (trustRaw["entries"] as List<Map<String, Any?>>).map {
            TrustEntry(
                keyId = it["key_id"] as String,
                publicKeyHex = it["public_key_hex"] as String,
                role = it["role"] as String,
                expiresAt = it["expires_at"] as String,
            )
        }

        val features = contents["features.json"]?.let { parseFeatures(it) } ?: emptyList()
        val indiaAreas = contents["india_admin1.json"]?.let { parseIndiaAreas(it) } ?: emptyList()
        val offlineMap = contents["offline_map.json"]?.let { parseOfflineMap(it) }

        return Pack(
            packVersion = manifest["pack_version"] as String,
            createdAt = createdAt,
            sourceClass = manifest["source_class"] as String,
            graph = graph,
            trust = TrustRegistry(entries),
            features = features,
            indiaAreas = indiaAreas,
            attribution = (contents["LICENSES.txt"]?.let { String(it, Charsets.UTF_8) } ?: "").trim(),
            offlineMap = offlineMap,
        )
    }

    private fun parseOfflineMap(bytes: ByteArray): OfflineMapMetadata {
        val map = asMap(Json.parse(String(bytes, Charsets.UTF_8)))
        val archive = map["archive_sha256"] as? String ?: throw PackException("offline map archive hash missing")
        val database = map["database_sha256"] as? String ?: throw PackException("offline map database hash missing")
        val size = (map["database_bytes"] as? Number)?.toLong() ?: throw PackException("offline map size missing")
        if (!archive.matches(Regex("[0-9a-f]{64}")) || !database.matches(Regex("[0-9a-f]{64}")) || size <= 0)
            throw PackException("offline map metadata invalid")
        return OfflineMapMetadata(archive, database, size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun parseFeatures(bytes: ByteArray): List<MapFeature> {
        val root = asMap(Json.parse(String(bytes, Charsets.UTF_8)))
        return (root["features"] as List<Map<String, Any?>>).map { f ->
            MapFeature(
                kind = f["kind"] as String,
                name = (f["name"] as? String) ?: "",
                coords = (f["coords"] as List<List<Number>>).map { it[0].toDouble() to it[1].toDouble() },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseIndiaAreas(bytes: ByteArray): List<IndiaAdminArea> {
        val root = asMap(Json.parse(String(bytes, Charsets.UTF_8)))
        val features = root["features"] as? List<Map<String, Any?>>
            ?: throw PackException("india_admin1.json has no features")
        return features.map { feature ->
            val rings = (feature["rings"] as? List<List<List<Number>>>)
                ?: throw PackException("invalid India map rings")
            val coords = rings.map { ring ->
                if (ring.size < 4) throw PackException("India map ring is too short")
                ring.map { point ->
                    if (point.size != 2) throw PackException("invalid India map point")
                    val lon = point[0].toDouble()
                    val lat = point[1].toDouble()
                    if (!lon.isFinite() || !lat.isFinite() || lon !in 60.0..100.0 || lat !in -5.0..45.0)
                        throw PackException("India map point is outside supported bounds")
                    lat to lon
                }
            }
            IndiaAdminArea(
                name = feature["name"] as? String ?: throw PackException("India map area has no name"),
                code = feature["code"] as? String ?: throw PackException("India map area has no code"),
                rings = coords,
            )
        }
    }
}
