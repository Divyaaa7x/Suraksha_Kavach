package org.kavach.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoutePolicyTest {
    @Test
    fun unknownCrossingsAreStrictlyBlockedAndOnlyWarningModeIncludesThem() {
        assertEquals(RouteCrossingDisposition.BLOCK, crossingDisposition(Status.UNKNOWN, Policy.STRICT))
        assertEquals(RouteCrossingDisposition.UNCERTAIN,
            crossingDisposition(Status.UNKNOWN, Policy.UNVERIFIED_WARNING))
        assertEquals(RouteCrossingDisposition.OPEN, crossingDisposition(Status.VERIFIED_OPEN, Policy.STRICT))
    }

    @Test
    fun closedAndRestrictedCrossingsStayBlockedInWarningMode() {
        assertEquals(RouteCrossingDisposition.BLOCK,
            crossingDisposition(Status.VERIFIED_CLOSED, Policy.UNVERIFIED_WARNING))
        assertEquals(RouteCrossingDisposition.BLOCK,
            crossingDisposition(Status.RESTRICTED, Policy.UNVERIFIED_WARNING))
    }

    @Test
    fun vehicleMasksAndOnewayDirectionsAreHardFilters() {
        assertTrue(canUseRouteEdge(0b001, "light", oneway = 1, forward = true))
        assertFalse(canUseRouteEdge(0b001, "medium", oneway = 1, forward = true))
        assertFalse(canUseRouteEdge(0b001, "light", oneway = 1, forward = false))
        assertTrue(canUseRouteEdge(0b010, "medium", oneway = 0, forward = false))
        assertFalse(canUseRouteEdge(0b001, "light", oneway = -1, forward = true))
        assertTrue(canUseRouteEdge(0b001, "light", oneway = -1, forward = false))
    }

    @Test
    fun haversineHeuristicDoesNotOverestimateTravelTime() {
        val startToGoal = routeHeuristicSeconds(17.4, 78.4, 18.0, 79.0, speedKmh = 90.0)
        val startToVia = routeHeuristicSeconds(17.4, 78.4, 17.7, 78.7, speedKmh = 90.0)
        val viaToGoal = routeHeuristicSeconds(17.7, 78.7, 18.0, 79.0, speedKmh = 90.0)
        assertTrue(startToGoal <= startToVia + viaToGoal + 0.001)
    }
}
