package de.samthedev.lpbsa.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import de.samthedev.lpbsa.access.AccessController
import net.kyori.adventure.text.Component
import org.slf4j.Logger

class ServerPreConnectListener(
    private val controller: AccessController,
    private val logger: Logger,
) {
    // Run after normal routing plugins so the final ServerResult destination is authorized.
    @Subscribe(priority = Short.MIN_VALUE, async = false)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        try {
            controller.handle(event)
        } catch (failure: Exception) {
            // No unexpected controller or rendering failure may leave the connection allowed.
            event.result = ServerPreConnectEvent.ServerResult.denied()
            try {
                if (event.previousServer == null) {
                    event.player.disconnect(Component.text("LPBSA encountered an internal error; backend access was denied."))
                } else {
                    event.player.sendMessage(Component.text("LPBSA encountered an internal error; that transfer was denied."))
                }
            } catch (_: Exception) {
                // The event denial above remains authoritative even if notification fails.
            }
            try {
                logger.error("[LPBSA] Unexpected connection-policy failure; access was denied.", failure)
            } catch (_: Exception) {
                // A logging implementation must not change the denial result.
            }
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        controller.clearCooldown(event.player.uniqueId)
    }
}
