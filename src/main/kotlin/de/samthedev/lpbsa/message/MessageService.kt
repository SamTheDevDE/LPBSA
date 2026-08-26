package de.samthedev.lpbsa.message

import de.samthedev.lpbsa.config.NotificationLocation
import de.samthedev.lpbsa.config.RuntimeStateStore
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

class MessageService(
    private val states: RuntimeStateStore,
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
) {
    fun render(key: String, values: Map<String, String> = emptyMap()): Component {
        val messages = states.current().messages
        val template = messages.template(key) ?: messages.template("fallback-unavailable") ?: "<red>Message unavailable."
        val prefix = miniMessage.deserialize(messages.prefix, dynamicResolver(values))
        val resolver = TagResolver.builder()
            .resolver(Placeholder.component("prefix", prefix))
            .resolver(dynamicResolver(values))
            .build()
        return miniMessage.deserialize(template, resolver)
    }

    fun send(audience: Audience, key: String, values: Map<String, String> = emptyMap()) {
        audience.sendMessage(render(key, values))
    }

    fun notify(
        audience: Audience,
        location: NotificationLocation,
        key: String,
        values: Map<String, String> = emptyMap(),
    ) {
        val component = render(key, values)
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
