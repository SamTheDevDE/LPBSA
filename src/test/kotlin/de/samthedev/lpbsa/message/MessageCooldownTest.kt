package de.samthedev.lpbsa.message

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageCooldownTest {
    @Test
    fun `cooldown is per player and target and never affects authorization`() {
        var now = 1_000L
        val cooldown = MessageCooldown { now }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(cooldown.shouldNotify(first, "build", 1_000))
        assertFalse(cooldown.shouldNotify(first, "build", 1_000))
        assertTrue(cooldown.shouldNotify(first, "staff", 1_000))
        assertTrue(cooldown.shouldNotify(second, "build", 1_000))
        now += 1_000
        assertTrue(cooldown.shouldNotify(first, "build", 1_000))
    }

    @Test
    fun `zero cooldown always notifies`() {
        val cooldown = MessageCooldown { 100L }
        val player = UUID.randomUUID()
        assertTrue(cooldown.shouldNotify(player, "build", 0))
        assertTrue(cooldown.shouldNotify(player, "build", 0))
    }
}
