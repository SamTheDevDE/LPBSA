package de.samthedev.lpbsa.access

import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class FallbackPlannerTest {
    private val planner = FallbackPlanner()

    @Test
    fun `accessible registered fallback redirects`() {
        assertIs<FallbackPlan.Redirect>(planner.plan("build", "lobby", setOf("build", "lobby"), AccessDecision.OpenServer))
    }

    @Test
    fun `fallback loop disconnects`() {
        assertIs<FallbackPlan.Disconnect>(planner.plan("build", "build", setOf("build"), AccessDecision.OpenServer))
    }

    @Test
    fun `missing fallback disconnects`() {
        assertIs<FallbackPlan.Disconnect>(planner.plan("build", "lobby", setOf("build"), null))
    }

    @Test
    fun `restricted fallback denial disconnects`() {
        assertIs<FallbackPlan.Disconnect>(
            planner.plan("build", "lobby", setOf("build", "lobby"), AccessDecision.Denied(DenialReason.EmptyRequirements)),
        )
    }
}
