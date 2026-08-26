package de.samthedev.lpbsa.luckperms

import net.luckperms.api.context.DefaultContextKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LuckPermsServiceTest {
    @Test
    fun `target context replaces current server and preserves custom contexts`() {
        val contexts = targetContexts(
            mapOf(
                DefaultContextKeys.SERVER_KEY to setOf("lobby"),
                "region" to setOf("eu"),
                "world" to setOf("spawn"),
            ),
            "Build",
        )

        assertEquals(setOf("build"), contexts[DefaultContextKeys.SERVER_KEY])
        assertEquals(setOf("eu"), contexts["region"])
        assertEquals(setOf("spawn"), contexts["world"])
        assertFalse(contexts[DefaultContextKeys.SERVER_KEY].orEmpty().contains("lobby"))
    }

    @Test
    fun `server context key matching is case insensitive`() {
        val contexts = targetContexts(mapOf("SERVER" to setOf("old")), "staff")

        assertEquals(mapOf(DefaultContextKeys.SERVER_KEY to setOf("staff")), contexts)
    }
}
