package de.samthedev.lpbsa.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import net.kyori.adventure.text.Component

class StartupFailClosedListener {
    @Subscribe(priority = Short.MIN_VALUE, async = false)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (!event.result.isAllowed) return
        event.result = ServerPreConnectEvent.ServerResult.denied()
        if (event.previousServer == null) {
            event.player.disconnect(Component.text("LPBSA failed to initialize; backend access is unavailable."))
        } else {
            event.player.sendMessage(Component.text("LPBSA failed to initialize; that backend transfer was denied."))
        }
    }
}
