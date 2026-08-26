package de.samthedev.lpbsa.access

import de.samthedev.lpbsa.config.*
import net.luckperms.api.util.Tristate
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessEvaluatorTest {
    private val evaluator = AccessEvaluator()

    @Test
    fun `open server is allowed`() {
        assertIs<AccessDecision.OpenServer>(evaluator.evaluate(config(), "lobby", subject()))
    }

    @Test
    fun `restricted server is denied without permission`() {
        assertFalse(evaluator.evaluate(config(rule = rule()), "build", subject()).allowed)
    }

    @Test
    fun `restricted server is allowed with permission`() {
        assertTrue(evaluator.evaluate(config(rule = rule()), "build", subject("lpbsa.server.build")).allowed)
    }

    @Test
    fun `permission removal is reflected on the next access attempt`() {
        val restricted = config(rule = rule())
        assertTrue(evaluator.evaluate(restricted, "build", subject("lpbsa.server.build")).allowed)
        assertFalse(evaluator.evaluate(restricted, "build", subject()).allowed)
    }

    @Test
    fun `global bypass allows restricted server`() {
        assertIs<AccessDecision.AllowedByGlobalBypass>(evaluator.evaluate(config(rule = rule()), "build", subject("lpbsa.bypass")))
    }

    @Test
    fun `server bypass allows only its server`() {
        val decision = evaluator.evaluate(config(rule = rule()), "build", subject("lpbsa.bypass.build"))
        assertIs<AccessDecision.AllowedByServerBypass>(decision)
        assertFalse(evaluator.evaluate(config(rule = ServerRule("staff", true, Requirements(RequirementMode.ANY, listOf("staff.access"), emptyList()))), "staff", subject("lpbsa.bypass.build")).allowed)
    }

    @Test
    fun `explicitly negated permission is denied`() {
        val negated = subject(values = mapOf("lpbsa.server.build" to Tristate.FALSE))
        assertFalse(evaluator.evaluate(config(rule = rule()), "build", negated).allowed)
    }

    @Test
    fun `undefined permission is denied`() {
        val undefined = subject(values = mapOf("lpbsa.server.build" to Tristate.UNDEFINED))
        assertFalse(evaluator.evaluate(config(rule = rule()), "build", undefined).allowed)
    }

    @Test
    fun `ANY accepts one matching requirement across permissions and groups`() {
        val requirements = Requirements(RequirementMode.ANY, listOf("network.admin"), listOf("builder"))
        assertTrue(evaluator.evaluate(config(rule = rule(requirements)), "build", subject(groups = setOf("builder"))).allowed)
    }

    @Test
    fun `ALL requires permissions and groups`() {
        val requirements = Requirements(RequirementMode.ALL, listOf("network.staff"), listOf("builder"))
        assertFalse(evaluator.evaluate(config(rule = rule(requirements)), "build", subject("network.staff")).allowed)
        assertTrue(evaluator.evaluate(config(rule = rule(requirements)), "build", subject("network.staff", groups = setOf("builder"))).allowed)
    }

    @Test
    fun `inherited group name is checked case-insensitively`() {
        val requirements = Requirements(RequirementMode.ANY, emptyList(), listOf("Builder"))
        assertTrue(evaluator.evaluate(config(rule = rule(requirements)), "build", subject(groups = setOf("builder"))).allowed)
    }

    @Test
    fun `empty explicit requirements fail closed`() {
        val decision = evaluator.evaluate(config(rule = rule(Requirements(RequirementMode.ANY, emptyList(), emptyList()))), "build", subject())
        assertIs<AccessDecision.Denied>(decision)
        assertIs<DenialReason.EmptyRequirements>(decision.reason)
    }

    @Test
    fun `default restricted policy derives server permission`() {
        val restricted = config(defaultPolicy = DefaultPolicy.RESTRICTED)
        assertFalse(evaluator.evaluate(restricted, "staff", subject()).allowed)
        assertTrue(evaluator.evaluate(restricted, "staff", subject("lpbsa.server.staff")).allowed)
    }

    @Test
    fun `authorization failures obey fail mode`() {
        val broken = object : PermissionSubject {
            override val name = "Steve"
            override fun permissionValue(permission: String): Tristate = error("broken provider")
            override fun isInGroup(group: String): Boolean = false
        }
        assertFalse(evaluator.evaluate(config(rule = rule()), "build", broken).allowed)
        assertTrue(evaluator.evaluate(config(rule = rule(), failMode = FailMode.OPEN), "build", broken).allowed)
    }

    private fun subject(
        vararg permissions: String,
        groups: Set<String> = emptySet(),
        values: Map<String, Tristate> = emptyMap(),
    ) = object : PermissionSubject {
        override val name = "Steve"
        override fun permissionValue(permission: String): Tristate =
            values[permission] ?: if (permission in permissions) Tristate.TRUE else Tristate.UNDEFINED
        override fun isInGroup(group: String) = groups.any { it.equals(group, ignoreCase = true) }
    }

    private fun rule(requirements: Requirements = Requirements(RequirementMode.ANY, listOf("lpbsa.server.build"), emptyList())) =
        ServerRule("build", true, requirements)

    private fun config(
        rule: ServerRule? = null,
        defaultPolicy: DefaultPolicy = DefaultPolicy.OPEN,
        failMode: FailMode = FailMode.CLOSED,
    ) = RuntimeConfig(
        1, false, defaultPolicy, "lpbsa.bypass", failMode, false, DenialSettings(), LoggingSettings(), emptyMap(),
        rule?.let { mapOf(it.server to it) } ?: emptyMap(), emptySet(),
    )
}
