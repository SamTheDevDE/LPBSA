package de.samthedev.lpbsa.message

import de.samthedev.lpbsa.config.DefaultPolicy
import de.samthedev.lpbsa.config.DenialSettings
import de.samthedev.lpbsa.config.FailMode
import de.samthedev.lpbsa.config.LoggingSettings
import de.samthedev.lpbsa.config.RuntimeConfig
import de.samthedev.lpbsa.config.RuntimeState
import de.samthedev.lpbsa.config.RuntimeStateStore
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class MessageServiceFailureTest {
    @Test
    fun `renderer failure returns a safe literal component`() {
        val broken = Proxy.newProxyInstance(
            MiniMessage::class.java.classLoader,
            arrayOf(MiniMessage::class.java),
        ) { _, _, _ -> throw IllegalArgumentException("broken parser") } as MiniMessage
        val state = RuntimeState(
            RuntimeConfig(
                1, false, DefaultPolicy.OPEN, "lpbsa.bypass", FailMode.CLOSED, false,
                DenialSettings(), LoggingSettings(), emptyMap(), emptyMap(), emptySet(),
            ),
            MessageTemplates(1, "", mapOf("fallback-unavailable" to "bad")),
        )

        val rendered = MessageService(RuntimeStateStore(state), miniMessage = broken).render("fallback-unavailable")

        assertEquals(
            "LPBSA could not render the configured message.",
            PlainTextComponentSerializer.plainText().serialize(rendered),
        )
    }
}
