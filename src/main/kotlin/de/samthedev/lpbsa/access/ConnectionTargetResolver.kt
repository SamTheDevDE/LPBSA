package de.samthedev.lpbsa.access

import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.server.RegisteredServer

object ConnectionTargetResolver {
    fun effectiveServer(event: ServerPreConnectEvent): RegisteredServer? {
        if (!event.result.isAllowed) return null
        return event.result.server.orElse(null)
    }
}
