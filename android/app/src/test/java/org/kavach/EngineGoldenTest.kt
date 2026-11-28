package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.graph.Engine
import org.kavach.graph.Policy
import org.kavach.graph.RoadGraph
import org.kavach.graph.Node
import org.kavach.graph.Edge
import org.kavach.graph.Status
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.kavach.graph.Approval
import org.kavach.graph.Report

/** Same golden expectations as tests/test_graph.py on the Python hub. SYNTHETIC fixture only. */
class EngineGoldenTest {

    private val graph: RoadGraph = RoadGraph.fromJson(
        File("src/test/resources/synthetic/graph.json").readText(Charsets.UTF_8)
    )
    private val unknown = graph.crossings.keys.associateWith { Status.UNKNOWN }

    private fun st(vararg pairs: Pair<String, String>) = unknown + pairs.toMap()

    @Test
    fun baselineShortestPathIgnoresStatus() {
        val r = Engine.shortestPath(graph, "A", "H")
        assertEquals(listOf("A", "J1", "H"), r.path)
        assertEquals(listOf("E1", "E2"), r.edges)
    }

    @Test
    fun vehicleAccessIsEnforcedInBaselineAndPlanner() {
        val restricted = RoadGraph.fromJson(
            File("src/test/resources/synthetic/graph.json").readText(Charsets.UTF_8)
                .replace("\"edge_id\": \"E1\",", "\"edge_id\": \"E1\",\n      \"allowed_vehicles\": [\"heavy\"],")
        )
        assertEquals(listOf("E3", "E4", "E5"), Engine.shortestPath(restricted, "A", "H").edges)
        assertEquals(listOf("E1", "E2"), Engine.shortestPath(restricted, "A", "H", "heavy").edges)
        val route = Engine.planRoute(restricted, unknown, "A", "H", policy = Policy.UNVERIFIED_WARNING)
        assertTrue(route.excluded.any { it.edgeId == "E1" && it.reason.contains("vehicle access denied") })
    }

    @Test
    fun unknownCrossingsExcludedUnderStrictPolicy() {
        val r = Engine.planRoute(graph, unknown, "A", "H")
        assertEquals("NO_VERIFIED_ROUTE", r.result)
        assertTrue(r.excluded.map { it.crossingId }.containsAll(listOf("C1", "C2", "C3")))
    }

    @Test
    fun verifiedClosedExcludedWithReason() {
        val r = Engine.planRoute(graph, st("C1" to Status.VERIFIED_CLOSED), "A", "H")
        assertEquals("HARD: verified closed", r.excluded.first { it.crossingId == "C1" }.reason)
    }

    @Test
    fun verifiedOpenRouteFoundWithTime() {
        val r = Engine.planRoute(graph, st("C1" to Status.VERIFIED_OPEN), "A", "H")
        assertEquals("ROUTE_CANDIDATE", r.result)
        assertEquals(listOf("E1", "E2"), r.edges)
        assertEquals(600.0, r.travelTimeS!!, 1e-9)
    }

    @Test
    fun closedPrimaryReroutesOverVerifiedAlternative() {
        val r = Engine.planRoute(
            graph,
            st("C1" to Status.VERIFIED_CLOSED, "C2" to Status.VERIFIED_OPEN, "C3" to Status.VERIFIED_OPEN),
            "A", "H",
        )
        assertEquals(listOf("E3", "E4", "E5"), r.edges)
    }

    @Test
    fun unverifiedWarningPolicyIncludesAndFlags() {
        val r = Engine.planRoute(graph, unknown, "A", "H", policy = Policy.UNVERIFIED_WARNING)
        assertEquals("ROUTE_CANDIDATE", r.result)
        assertTrue(r.uncertain.isNotEmpty())
    }

    @Test
    fun isolationFindsSettlementsWithoutVerifiedPath() {
        assertEquals(listOf("A", "C"), Engine.isolated(graph, unknown).map { it.settlementId })
    }

    @Test
    fun oneWayRoadAwayFromFacilityDoesNotClaimSettlementAccess() {
        val directed = RoadGraph(
            nodes = mapOf(
                "S" to Node("S", "settlement", 17.0, 78.0, "Village", 100),
                "F" to Node("F", "facility", 17.1, 78.1, "Clinic", null),
            ),
            edges = mapOf("E" to Edge("E", "F", "S", 1000.0, 30.0, "C", true)),
            crossings = mapOf("C" to org.kavach.graph.Crossing("C", "bridge", emptySet())),
            sourceClass = "SIMULATED",
            version = "directional-test",
        )

        assertEquals(
            listOf("S"),
            Engine.isolated(directed, mapOf("C" to Status.VERIFIED_OPEN)).map { it.settlementId },
        )
        assertEquals(0, Engine.criticality(directed).getValue("C"))

        val towardFacility = RoadGraph(
            nodes = directed.nodes,
            edges = mapOf("E" to Edge("E", "S", "F", 1000.0, 30.0, "C", true)),
            crossings = directed.crossings,
            sourceClass = "SIMULATED",
            version = "directional-test-forward",
        )
        assertEquals(
            emptyList<String>(),
            Engine.isolated(towardFacility, mapOf("C" to Status.VERIFIED_OPEN)).map { it.settlementId },
        )
        assertEquals(1, Engine.criticality(towardFacility).getValue("C"))
    }

    @Test
    fun criticalityFindsSinglePathCrossing() {
        val crit = Engine.criticality(graph)
        assertEquals(1, crit["C4"])
        assertEquals(0, crit["C1"])
        assertEquals(0, crit["C2"])
    }

    @Test
    fun inspectionRankingPutsCriticalUnknownFirst() {
        val rows = Engine.inspectionRanking(graph, unknown)
        assertEquals("C4", rows.first().crossingId)
        assertEquals(1, rows.first().cutOff)
    }

    @Test
    fun approvedClosureNotOverwrittenByLaterOpenReport() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val reports = listOf(Report("r1", "C1", "OPEN", now.plusHours(1).toString()))
        val approvals = listOf(Approval("a1", "C1", Status.VERIFIED_CLOSED, now.toString(), now.toString(), "demo-supervisor-01"))
        val view = Engine.resolveStatus(graph.crossings.keys, reports, approvals, now)
        assertEquals(Status.VERIFIED_CLOSED, view.getValue("C1").status)
        assertTrue(view.getValue("C1").conflictWithReports)
    }

    @Test
    fun conflictingReportsWithoutApprovalAreDisputed() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val reports = listOf(
            Report("a", "C2", "BLOCKAGE", now.toString()),
            Report("b", "C2", "OPEN", now.toString()),
        )
        assertEquals(Status.DISPUTED, Engine.resolveStatus(graph.crossings.keys, reports, emptyList(), now).getValue("C2").status)
    }

    @Test
    fun staleVerifiedOpenIsDemoted() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val old = now.minusDays(90).toString()
        val approvals = listOf(Approval("x", "C3", Status.VERIFIED_OPEN, old, old, "demo-supervisor-01"))
        assertEquals(Status.STALE, Engine.resolveStatus(graph.crossings.keys, emptyList(), approvals, now).getValue("C3").status)
        assertTrue(Duration.ofDays(30) < Duration.between(OffsetDateTime.parse(old), now))
    }
}
