package org.kavach.graph

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.PriorityQueue
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OfflineRoutePlace(val id: Long, val name: String, val kind: String, val nodeId: Long?)

internal enum class RouteCrossingDisposition { OPEN, BLOCK, UNCERTAIN }

internal fun crossingDisposition(status: String, policy: String): RouteCrossingDisposition = when {
    status == Status.VERIFIED_OPEN -> RouteCrossingDisposition.OPEN
    status == Status.VERIFIED_CLOSED || status == Status.RESTRICTED -> RouteCrossingDisposition.BLOCK
    policy == Policy.UNVERIFIED_WARNING -> RouteCrossingDisposition.UNCERTAIN
    else -> RouteCrossingDisposition.BLOCK
}

internal fun canUseRouteEdge(vehicleMask: Int, vehicle: String, oneway: Int, forward: Boolean): Boolean {
    val bit = when (vehicle) { "light" -> 1; "medium" -> 2; "heavy" -> 4; else -> return false }
    return (vehicleMask and bit) != 0 && !(if (forward) oneway == -1 else oneway == 1)
}

internal fun routeHeuristicSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double, speedKmh: Double): Double {
    require(speedKmh.isFinite() && speedKmh > 0.0)
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    val distance = 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    return distance / (speedKmh / 3.6)
}

/** Reads the separately hash-verified OSM route database. OSM crossing tags never imply passability. */
class OfflineRouteStore(private val file: File) {
    private data class QueueNode(val estimate: Double, val cost: Double, val id: Long)
    fun searchPlaces(query: String, limit: Int = 20): List<OfflineRoutePlace> {
        val prefix = query.trim().take(100)
        if (prefix.length < 2) return emptyList()
        val db = open()
        return try {
            db.rawQuery(
                "SELECT place_id,name,kind,node_id FROM route_places " +
                    "WHERE node_id IS NOT NULL AND name>=? COLLATE NOCASE AND name<? COLLATE NOCASE " +
                    "ORDER BY name COLLATE NOCASE LIMIT ?",
                arrayOf(prefix, "$prefix\uFFFF", limit.coerceIn(1, 50).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        OfflineRoutePlace(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                            if (cursor.isNull(3)) null else cursor.getLong(3)),
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    fun coordinates(nodeIds: List<String>): List<Pair<Double, Double>> {
        val ids = nodeIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        val db = open()
        return try {
            ids.chunked(500).flatMap { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                val coordinates = HashMap<Long, Pair<Double, Double>>()
                db.rawQuery("SELECT osm_id,lat,lon FROM route_nodes WHERE osm_id IN ($marks)",
                    chunk.map(Long::toString).toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) coordinates[cursor.getLong(0)] = cursor.getDouble(1) to cursor.getDouble(2)
                }
                chunk.mapNotNull(coordinates::get)
            }
        } finally {
            db.close()
        }
    }

    fun route(origin: OfflineRoutePlace, destination: OfflineRoutePlace, vehicle: String, policy: String,
              statuses: Map<String, String>): RouteResult {
        require(vehicle in VEHICLES) { "Unsupported vehicle class" }
        require(policy == Policy.STRICT || policy == Policy.UNVERIFIED_WARNING) { "Unsupported route policy" }
        val start = requireNotNull(origin.nodeId) { "Origin is not connected to the road graph" }
        val goal = requireNotNull(destination.nodeId) { "Destination is not connected to the road graph" }
        require(start != goal) { "Choose different origins and destinations" }
        val db = open()
        try {
            val best = HashMap<Long, Double>()
            val previous = HashMap<Long, Previous>()
            val excluded = linkedMapOf<String, Excluded>()
            val target = nodeCoordinates(db, goal) ?: throw IllegalStateException("Destination road node is missing")
            val maximumSpeed = MAX_ROUTE_SPEED_KMH
            val queue = PriorityQueue<QueueNode>(compareBy<QueueNode> { it.estimate }.thenBy { it.cost }.thenBy { it.id })
            best[start] = 0.0
            val startCoordinate = nodeCoordinates(db, start) ?: throw IllegalStateException("Origin road node is missing")
            queue.add(QueueNode(routeHeuristicSeconds(startCoordinate.first, startCoordinate.second,
                target.first, target.second, maximumSpeed), 0.0, start))
            var expanded = 0
            while (queue.isNotEmpty()) {
                val (_, cost, node) = queue.remove()
                if (cost != best[node]) continue
                if (node == goal) break
                if (++expanded > MAX_EXPANSIONS) throw IllegalStateException("Route search reached its safety limit; no route returned")
                visit(db, "e.u=?", node, reverse = false, vehicle, policy, statuses, excluded) { next, edge, seconds, crossingId, status, lat, lon ->
                    relax(node, next, edge, seconds, crossingId, status, lat, lon, target, maximumSpeed, cost, best, previous, queue)
                }
                visit(db, "e.v=?", node, reverse = true, vehicle, policy, statuses, excluded) { next, edge, seconds, crossingId, status, lat, lon ->
                    relax(node, next, edge, seconds, crossingId, status, lat, lon, target, maximumSpeed, cost, best, previous, queue)
                }
            }
            if (goal !in best) return RouteResult(
                "NO_VERIFIED_ROUTE", emptyList(), emptyList(), null,
                excluded.values.toList(), emptyList(), "osm-statewide", policy,
                if (policy == Policy.STRICT) "No route satisfies the crossing policy. Request field verification; do not navigate."
                else "No route found in the OSM road graph. OSM coverage may be incomplete; do not navigate.",
            )

            val edges = ArrayList<String>()
            val path = ArrayList<String>()
            val routeUncertain = linkedMapOf<String, Excluded>()
            var current = goal
            path.add(current.toString())
            while (current != start) {
                val step = previous[current] ?: error("Route predecessor chain is incomplete")
                edges.add(step.edgeId)
                if (step.crossingId != null && step.status != Status.VERIFIED_OPEN) {
                    routeUncertain.putIfAbsent(step.edgeId,
                        Excluded(step.edgeId, step.crossingId, "unverified (${step.status}): included with warning"))
                }
                current = step.from
                path.add(current.toString())
            }
            edges.reverse()
            path.reverse()
            val caveat = if (routeUncertain.isNotEmpty())
                "Candidate includes crossings without verified-open status. Human review and field verification required; not navigation guidance."
            else "Mapped tagged crossings on this candidate are verified open. OSM completeness and structural safety remain unverified; this is not navigation guidance."
            return RouteResult("ROUTE_CANDIDATE", path, edges, best.getValue(goal), excluded.values.toList(),
                routeUncertain.values.take(MAX_RECORDED_EDGES).toList(), "osm-statewide", policy, caveat)
        } finally {
            db.close()
        }
    }

    private data class Previous(val from: Long, val edgeId: String, val crossingId: String?, val status: String?)

    private fun nodeCoordinates(db: SQLiteDatabase, id: Long): Pair<Double, Double>? =
        db.rawQuery("SELECT lat,lon FROM route_nodes WHERE osm_id=?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) to cursor.getDouble(1) else null
        }

    private fun visit(db: SQLiteDatabase, where: String, node: Long, reverse: Boolean, vehicle: String, policy: String,
                      statuses: Map<String, String>, excluded: MutableMap<String, Excluded>,
                      consume: (Long, String, Double, String?, String?, Double, Double) -> Unit) {
        val nextNode = if (reverse) "e.u" else "e.v"
        val sql = "SELECT e.way_id,e.segment_index,e.u,e.v,e.length_m,e.speed_kmh,e.oneway,e.vehicle_mask,e.crossing_way_id,n.lat,n.lon " +
            "FROM route_edges e JOIN route_nodes n ON n.osm_id=$nextNode WHERE $where"
        db.rawQuery(sql, arrayOf(node.toString())).use { rows ->
            while (rows.moveToNext()) {
                val u = rows.getLong(2)
                val v = rows.getLong(3)
                val oneway = rows.getInt(6)
                if (!canUseRouteEdge(rows.getInt(7), vehicle, oneway, forward = !reverse)) continue
                val edgeId = "R${rows.getLong(0)}.${rows.getInt(1)}"
                val crossingId = if (rows.isNull(8)) null else "OSM-W${rows.getLong(8)}"
                var crossingStatus: String? = null
                if (crossingId != null) {
                    val status = statuses[crossingId] ?: Status.UNKNOWN
                    crossingStatus = status
                    if (crossingDisposition(status, policy) == RouteCrossingDisposition.BLOCK) {
                        if (excluded.size < MAX_RECORDED_EDGES) excluded.putIfAbsent(edgeId,
                            Excluded(edgeId, crossingId, when (status) {
                                Status.VERIFIED_CLOSED -> "HARD: verified closed"
                                Status.RESTRICTED -> "HARD: restriction is not modeled for this vehicle"
                                else -> "CONSERVATIVE: status $status is not verified"
                            }))
                        continue
                    }
                }
                val length = rows.getDouble(4)
                val speed = rows.getDouble(5)
                if (!length.isFinite() || length <= 0 || !speed.isFinite() || speed <= 0) continue
                if (speed > MAX_ROUTE_SPEED_KMH) throw IllegalStateException("Route database speed exceeds the signed routing profile")
                consume(if (reverse) u else v, edgeId, length / speed * 3.6, crossingId, crossingStatus,
                    rows.getDouble(9), rows.getDouble(10))
            }
        }
    }

    private fun relax(from: Long, to: Long, edge: String, seconds: Double, crossingId: String?, status: String?,
                      lat: Double, lon: Double, target: Pair<Double, Double>, maximumSpeed: Double, cost: Double,
                      best: MutableMap<Long, Double>, previous: MutableMap<Long, Previous>,
                      queue: PriorityQueue<QueueNode>) {
        val next = cost + seconds
        if (next >= (best[to] ?: Double.POSITIVE_INFINITY)) return
        best[to] = next
        previous[to] = Previous(from, edge, crossingId, status)
        queue.add(QueueNode(next + routeHeuristicSeconds(lat, lon, target.first, target.second, maximumSpeed), next, to))
    }

    private fun open() = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    companion object {
        private const val MAX_EXPANSIONS = 1_000_000
        private const val MAX_RECORDED_EDGES = 200
        // The statewide builder's fastest emitted road profile is 90 km/h; keep A* admissible.
        private const val MAX_ROUTE_SPEED_KMH = 90.0
        private val VEHICLES = setOf("light", "medium", "heavy")
    }
}
