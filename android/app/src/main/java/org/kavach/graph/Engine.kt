package org.kavach.graph

import java.time.Duration
import java.time.OffsetDateTime
import java.util.PriorityQueue

/**
 * Kotlin port of server/kavach/graph.py. Shared golden tests keep the two implementations in step
 * (see app/src/test/.../EngineGoldenTest.kt, which uses the same fixture as tests/test_graph.py).
 */
data class Excluded(val edgeId: String, val crossingId: String?, val reason: String)

data class RouteResult(
    val result: String,              // ROUTE_CANDIDATE | NO_VERIFIED_ROUTE
    val path: List<String>,
    val edges: List<String>,
    val travelTimeS: Double?,
    val excluded: List<Excluded>,
    val uncertain: List<Excluded>,
    val graphVersion: String,
    val policy: String,
    val caveat: String,
)

data class ShortestPath(val path: List<String>, val edges: List<String>, val travelTimeS: Double?)

data class Isolated(val settlementId: String, val name: String, val population: Int?)

data class InspectionRow(val crossingId: String, val status: String, val cutOff: Int)

data class StatusView(val status: String, val conflictWithReports: Boolean, val basis: String?)

data class Report(val eventId: String, val assetId: String, val observationType: String, val receivedAt: String)

data class Approval(
    val eventId: String,
    val assetId: String,
    val approvedStatus: String,
    val approvedAt: String,
    val receivedAt: String,
    val originKeyId: String,
)

object Engine {
    const val MAX_SPEED_KMH = 80.0
    private val CLOSED_TYPES = setOf("WATER", "BLOCKAGE", "CLOSED")

    private data class Decision(val eligible: Boolean, val reason: String, val uncertain: Boolean)

    private fun edgeDecision(status: String, vehicle: String, crossing: Crossing?, policy: String): Decision {
        if (crossing == null) return Decision(true, "no crossing", false)
        return when (status) {
            Status.VERIFIED_CLOSED -> Decision(false, "HARD: verified closed", false)
            Status.VERIFIED_OPEN -> Decision(true, "verified open", false)
            Status.RESTRICTED ->
                if (vehicle in crossing.restrictTo) Decision(true, "restricted: $vehicle permitted", false)
                else Decision(false, "HARD: restricted, $vehicle not permitted", false)
            Status.PREDICTED_RISK -> Decision(true, "warning only: predicted risk", true)
            else ->
                if (policy == Policy.UNVERIFIED_WARNING)
                    Decision(true, "unverified ($status): included with warning", true)
                else Decision(false, "CONSERVATIVE: status $status is not verified", false)
        }
    }

    /** A* over edges that are allowed. Heuristic = straight-line distance / max speed (admissible). */
    private fun astar(graph: RoadGraph, origin: String, dest: String, allowed: Set<String>): Pair<Double, List<Edge>>? {
        val adj = graph.adjacency()
        val dnode = graph.nodes.getValue(dest)
        val maxMs = MAX_SPEED_KMH / 3.6
        fun h(n: String) = haversineM(graph.nodes.getValue(n), dnode) / maxMs

        val best = HashMap<String, Double>().apply { put(origin, 0.0) }
        val prev = HashMap<String, Pair<String, Edge>>()
        data class Item(val f: Double, val g: Double, val node: String)
        val pq = PriorityQueue<Item>(compareBy<Item> { it.f }.thenBy { it.g }.thenBy { it.node })
        pq.add(Item(h(origin), 0.0, origin))
        while (pq.isNotEmpty()) {
            val (_, g, n) = pq.poll()!!
            if (n == dest) {
                val edges = ArrayList<Edge>()
                var cur = dest
                while (cur != origin) {
                    val (pn, pe) = prev.getValue(cur)
                    edges.add(pe)
                    cur = pn
                }
                edges.reverse()
                return g to edges
            }
            if (g > (best[n] ?: Double.POSITIVE_INFINITY)) continue
            for (e in adj.getValue(n)) {
                if (e.id !in allowed) continue
                val m = if (e.u == n) e.v else e.u
                val ng = g + travelTimeS(e)
                if (ng < (best[m] ?: Double.POSITIVE_INFINITY)) {
                    best[m] = ng
                    prev[m] = n to e
                    pq.add(Item(ng + h(m), ng, m))
                }
            }
        }
        return null
    }

    private fun sequence(origin: String, edges: List<Edge>): List<String> {
        val seq = arrayListOf(origin)
        var cur = origin
        for (e in edges) {
            cur = if (e.u == cur) e.v else e.u
            seq.add(cur)
        }
        return seq
    }

    fun planRoute(
        graph: RoadGraph,
        statuses: Map<String, String>,
        origin: String,
        dest: String,
        vehicle: String = "light",
        policy: String = Policy.STRICT,
    ): RouteResult {
        val allowed = HashSet<String>()
        val excluded = ArrayList<Excluded>()
        val uncertain = ArrayList<Excluded>()
        for (e in graph.edges.values.sortedBy { it.id }) {
            val crossing = e.crossingId?.let { graph.crossings[it] }
            val status = e.crossingId?.let { statuses[it] ?: Status.UNKNOWN } ?: Status.UNKNOWN
            val d = edgeDecision(status, vehicle, crossing, policy)
            if (d.eligible) allowed.add(e.id) else excluded.add(Excluded(e.id, e.crossingId, d.reason))
            if (d.eligible && d.uncertain) uncertain.add(Excluded(e.id, e.crossingId, d.reason))
        }
        val found = astar(graph, origin, dest, allowed)
        if (found == null) {
            return RouteResult("NO_VERIFIED_ROUTE", emptyList(), emptyList(), null, excluded, uncertain,
                graph.version, policy,
                "No route satisfies the policy. Request verification; do not navigate.")
        }
        val (cost, edges) = found
        val caveat = if (uncertain.isNotEmpty() && policy == Policy.UNVERIFIED_WARNING)
            "Route uses unverified crossings; human review required."
        else "Passability is not structural safety; human review required."
        return RouteResult("ROUTE_CANDIDATE", sequence(origin, edges), edges.map { it.id },
            Math.round(cost * 10) / 10.0, excluded, uncertain, graph.version, policy, caveat)
    }

    fun shortestPath(graph: RoadGraph, origin: String, dest: String): ShortestPath {
        val all = graph.edges.keys.toSet()
        val found = astar(graph, origin, dest, all) ?: return ShortestPath(emptyList(), emptyList(), null)
        return ShortestPath(sequence(origin, found.second), found.second.map { it.id },
            Math.round(found.first * 10) / 10.0)
    }

    private class Dsu(items: Collection<String>) {
        private val p = items.associateWith { it }.toMutableMap()
        fun find(x: String): String {
            var y = x
            while (p.getValue(y) != y) {
                p[y] = p.getValue(p.getValue(y))
                y = p.getValue(y)
            }
            return y
        }
        fun union(a: String, b: String) {
            p[find(a)] = find(b)
        }
    }

    fun isolated(
        graph: RoadGraph,
        statuses: Map<String, String>,
        vehicle: String = "light",
        policy: String = Policy.STRICT,
        blockedCrossings: Set<String> = emptySet(),
    ): List<Isolated> {
        val usable = HashSet<String>()
        for (e in graph.edges.values) {
            if (e.crossingId != null && e.crossingId in blockedCrossings) continue
            if (e.crossingId != null) {
                val d = edgeDecision(statuses[e.crossingId] ?: Status.UNKNOWN, vehicle,
                    graph.crossings[e.crossingId], policy)
                if (!d.eligible) continue
            }
            usable.add(e.id)
        }
        val dsu = Dsu(graph.nodes.keys)
        for (id in usable) {
            val e = graph.edges.getValue(id)
            dsu.union(e.u, e.v)
        }
        val facilityRoots = graph.nodes.values.filter { it.kind == "facility" }.map { dsu.find(it.id) }.toSet()
        return graph.nodes.values.filter { it.kind == "settlement" && dsu.find(it.id) !in facilityRoots }
            .sortedBy { it.id }
            .map { Isolated(it.id, it.name, it.population) }
    }

    /** Settlements that lose facility access when one crossing is removed, all others assumed passable. */
    fun criticality(graph: RoadGraph): Map<String, Int> {
        graph.precomputedCriticality?.let { return it }
        val allOpen = graph.crossings.keys.associateWith { Status.VERIFIED_OPEN }
        val base = isolated(graph, allOpen).map { it.settlementId }.toSet()
        return graph.crossings.keys.sorted().associateWith { c ->
            val cut = isolated(graph, allOpen, blockedCrossings = setOf(c)).map { it.settlementId }.toSet()
            (cut - base).size
        }
    }

    fun inspectionRanking(graph: RoadGraph, statuses: Map<String, String>): List<InspectionRow> {
        val unverified = setOf(Status.UNKNOWN, Status.DISPUTED, Status.REPORTED_OPEN, Status.REPORTED_CLOSED, Status.STALE)
        return criticality(graph).mapNotNull { (c, score) ->
            val st = statuses[c] ?: Status.UNKNOWN
            if (st in unverified) InspectionRow(c, st, score) else null
        }.sortedWith(compareBy<InspectionRow> { -it.cutOff }.thenBy { it.crossingId })
    }

    /** Port of resolve_status(): approvals outrank reports; conflicts with no approval -> DISPUTED. */
    fun resolveStatus(
        crossings: Collection<String>,
        reports: List<Report>,
        approvals: List<Approval>,
        now: OffsetDateTime,
        freshnessDays: Long = 30,
    ): Map<String, StatusView> {
        val latestApproval = HashMap<String, Approval>()
        for (a in approvals) {
            val cur = latestApproval[a.assetId]
            if (cur == null || a.receivedAt > cur.receivedAt) latestApproval[a.assetId] = a
        }
        val out = LinkedHashMap<String, StatusView>()
        for (asset in crossings.sorted()) {
            val rs = reports.filter { it.assetId == asset && it.observationType != "UNKNOWN" }
            val hasClosed = rs.any { it.observationType in CLOSED_TYPES }
            val hasOpen = rs.any { it.observationType == "OPEN" }
            val ap = latestApproval[asset]
            when {
                ap != null -> {
                    var state = ap.approvedStatus
                    val approvedClosed = state == Status.VERIFIED_CLOSED
                    val contradicts = rs.any { (it.observationType == "OPEN") == approvedClosed }
                    if (state == Status.VERIFIED_OPEN) {
                        val approvedWhen = OffsetDateTime.parse(ap.approvedAt)
                        if (Duration.between(approvedWhen, now) > Duration.ofDays(freshnessDays)) state = Status.STALE
                    }
                    out[asset] = StatusView(state, contradicts, ap.eventId)
                }
                hasClosed && hasOpen -> out[asset] = StatusView(Status.DISPUTED, true, null)
                rs.isNotEmpty() -> {
                    val latest = rs.maxBy { it.receivedAt }
                    val st = if (latest.observationType in CLOSED_TYPES) Status.REPORTED_CLOSED else Status.REPORTED_OPEN
                    out[asset] = StatusView(st, false, latest.eventId)
                }
                else -> out[asset] = StatusView(Status.UNKNOWN, false, null)
            }
        }
        return out
    }
}
