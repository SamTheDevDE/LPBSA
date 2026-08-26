package de.samthedev.lpbsa.config

import de.samthedev.lpbsa.message.MessageTemplates
import java.util.concurrent.atomic.AtomicReference

data class RuntimeState(
    val config: RuntimeConfig,
    val messages: MessageTemplates,
)

class RuntimeStateStore(initial: RuntimeState) {
    private val active = AtomicReference(initial)

    fun current(): RuntimeState = active.get()

    fun replace(next: RuntimeState) {
        active.set(next)
    }
}
