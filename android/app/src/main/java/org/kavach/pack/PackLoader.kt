package org.kavach.pack

import org.kavach.core.Crypto
import org.kavach.core.Json
import org.kavach.core.TrustEntry
import org.kavach.core.TrustRegistry
import org.kavach.graph.RoadGraph

class PackException(message: String) : Exception(message)

/** A water line for the map: river, stream, canal or drain (OpenStreetMap, ODbL). */
data class MapFeature(val kind: String, val name: String, val coords: List<Pair<Double, Double>>)

data class Pack(
    val packVersion: String,
    val createdAt: String,
    val sourceClass: String,
    val graph: RoadGraph,
    val trust: TrustRegistry,
    val features: List<MapFeature>,
    val attribution: String,
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

        return Pack(
            packVersion = manifest["pack_version"] as String,
            createdAt = createdAt,
            sourceClass = manifest["source_class"] as String,
            graph = graph,
            trust = TrustRegistry(entries),
            features = features,
            attribution = (contents["LICENSES.txt"]?.let { String(it, Charsets.UTF_8) } ?: "").trim(),
        )
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
}
