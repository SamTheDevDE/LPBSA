package de.samthedev.lpbsa.message

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessageCooldown(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val player: UUID, val server: String)

    private val lastMessages = ConcurrentHashMap<Key, Long>()

    fun shouldNotify(player: UUID, server: String, cooldownMs: Long): Boolean {
        if (cooldownMs <= 0) return true
        val now = clock()
        val key = Key(player, server)
        var permitted = false
        lastMessages.compute(key) { _, previous ->
            if (previous == null || now - previous >= cooldownMs) {
                permitted = true
                now
            } else {
                previous
            }
        }
        if (lastMessages.size > 1_000) cleanup(now, maxOf(60_000, cooldownMs * 4))
        return permitted
    }

    fun remove(player: UUID) {
        lastMessages.keys.removeIf { it.player == player }
    }

    private fun cleanup(now: Long, maximumAge: Long) {
        lastMessages.entries.removeIf { now - it.value > maximumAge }
    }
}
