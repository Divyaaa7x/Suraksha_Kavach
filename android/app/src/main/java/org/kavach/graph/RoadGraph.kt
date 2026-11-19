package org.kavach.graph

import org.kavach.core.Crypto
import org.kavach.core.Json
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Crossing states (dossier section 36). Same strings as the hub, so JSON and events match. */
object Status {
    const val UNKNOWN = "UNKNOWN"
    const val REPORTED_OPEN = "REPORTED_OPEN"
    const val REPORTED_CLOSED = "REPORTED_CLOSED"
    const val VERIFIED_OPEN = "VERIFIED_OPEN"
    const val VERIFIED_CLOSED = "VERIFIED_CLOSED"
    const val RESTRICTED = "RESTRICTED"
    const val DISPUTED = "DISPUTED"
    const val STALE = "STALE"
    const val PREDICTED_RISK = "PREDICTED_RISK"
}

object Policy {
    const val STRICT = "strict"
    const val UNVERIFIED_WARNING = "unverified_warning"
}

data class Node(
    val id: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val name: String,
    val population: Int?,
)

data class Edge(
    val id: String,
    val u: String,
    val v: String,
    val lengthM: Double,
    val speedKmh: Double,
    val crossingId: String?,
    val oneway: Boolean,
)

data class Crossing(val id: String, val kind: String, val restrictTo: Set<String>)

class RoadGraph(
    val nodes: Map<String, Node>,
    val edges: Map<String, Edge>,
    val crossings: Map<String, Crossing>,
    val sourceClass: String,
    val version: String,
    /** Criticality precomputed at pack build time (criticality.json). Null means compute on demand. */
    val precomputedCriticality: Map<String, Int>? = null,
) {
    fun adjacency(blocked: Set<String> = emptySet()): Map<String, List<Edge>> {
        val adj = nodes.keys.associateWith { mutableListOf<Edge>() }
        for (e in edges.values) {
            if (e.id in blocked) continue
            adj.getValue(e.u).add(e)
            if (!e.oneway) adj.getValue(e.v).add(e)
        }
        return adj
    }

    companion object {
        /** Parse graph.json as produced by server/kavach/pack.py. */
        fun fromJson(text: String, version: String? = null, criticality: Map<String, Int>? = null): RoadGraph {
            @Suppress("UNCHECKED_CAST")
            val root = Json.parse(text) as Map<String, Any?>
            val nodes = (root["nodes"] as List<Map<String, Any?>>).map {
                Node(
                    id = it["node_id"] as String,
                    kind = it["kind"] as String,
                    lat = num(it["lat"]),
                    lon = num(it["lon"]),
                    name = (it["name"] as? String) ?: "",
                    population = (it["population"] as? Number)?.toInt(),
                )
            }.associateBy { it.id }
            val edges = (root["edges"] as List<Map<String, Any?>>).map {
                Edge(
                    id = it["edge_id"] as String,
                    u = it["u"] as String,
                    v = it["v"] as String,
                    lengthM = num(it["length_m"]),
                    speedKmh = num(it["speed_kmh"]),
                    crossingId = it["crossing_id"] as String?,
                    oneway = it["oneway"] as Boolean,
                )
            }.associateBy { it.id }
            val crossings = ((root["crossings"] as? List<Map<String, Any?>>) ?: emptyList()).map {
                Crossing(
                    id = it["crossing_id"] as String,
                    kind = (it["kind"] as? String) ?: "culvert",
                    restrictTo = ((it["restrict_to"] as? List<*>) ?: emptyList<Any>()).map { v -> v as String }.toSet(),
                )
            }.associateBy { it.id }
            return RoadGraph(
                nodes = nodes,
                edges = edges,
                crossings = crossings,
                sourceClass = (root["source_class"] as? String) ?: "SIMULATED",
                version = version ?: versionOf(nodes.values, edges.values),
                precomputedCriticality = criticality,
            )
        }

        private fun num(v: Any?): Double = (v as Number).toDouble()

        /** Same idea as the hub: a short hash of the graph's structure, so routes can say which graph they used. */
        private fun versionOf(nodes: Collection<Node>, edges: Collection<Edge>): String {
            val parts = StringBuilder()
            nodes.sortedBy { it.id }.forEach { parts.append("N|${it.id}|${it.kind}|${it.lat}|${it.lon};") }
            edges.sortedBy { it.id }.forEach {
                parts.append("E|${it.id}|${it.u}|${it.v}|${it.lengthM}|${it.speedKmh}|${it.crossingId}|${it.oneway};")
            }
            return "g-" + Crypto.sha256Hex(parts.toString().toByteArray()).take(12)
        }
    }
}

fun travelTimeS(e: Edge): Double = e.lengthM / (e.speedKmh / 3.6)

fun haversineM(a: Node, b: Node): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dp = p2 - p1
    val dl = Math.toRadians(b.lon - a.lon)
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
