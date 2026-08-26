package de.samthedev.lpbsa.message

import de.samthedev.lpbsa.config.NotificationLocation
import de.samthedev.lpbsa.config.RuntimeStateStore
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.slf4j.Logger

class MessageService(
    private val states: RuntimeStateStore,
    private val logger: Logger? = null,
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
) {
    fun render(key: String, values: Map<String, String> = emptyMap()): Component =
        render(states.current().messages, key, values)

    fun render(messages: MessageTemplates, key: String, values: Map<String, String> = emptyMap()): Component =
        try {
            val template = messages.template(key) ?: messages.template("fallback-unavailable") ?: "<red>Message unavailable."
            val prefix = miniMessage.deserialize(messages.prefix, dynamicResolver(values))
            val resolver = TagResolver.builder()
                .resolver(Placeholder.component("prefix", prefix))
                .resolver(dynamicResolver(values))
                .build()
            miniMessage.deserialize(template, resolver)
        } catch (failure: Exception) {
            logger?.error("[LPBSA] Failed to render message {}.", key, failure)
            Component.text("LPBSA could not render the configured message.")
        }

    fun send(audience: Audience, key: String, values: Map<String, String> = emptyMap()) {
        audience.sendMessage(render(key, values))
    }

    fun notify(
        audience: Audience,
        location: NotificationLocation,
        key: String,
        values: Map<String, String> = emptyMap(),
    ) = notify(audience, location, states.current().messages, key, values)

    fun notify(
        audience: Audience,
        location: NotificationLocation,
        templates: MessageTemplates,
        key: String,
        values: Map<String, String> = emptyMap(),
    ) {
        val component = render(templates, key, values)
        when (location) {
            NotificationLocation.CHAT -> audience.sendMessage(component)
            NotificationLocation.ACTION_BAR -> audience.sendActionBar(component)
            NotificationLocation.BOTH -> {
                audience.sendMessage(component)
                audience.sendActionBar(component)
            }
            NotificationLocation.NONE -> Unit
        }
    }

    private fun dynamicResolver(values: Map<String, String>): TagResolver {
        val builder = TagResolver.builder()
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, Component.text(value))) }
        return builder.build()
    }
}
