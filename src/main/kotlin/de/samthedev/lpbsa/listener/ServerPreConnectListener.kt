package de.samthedev.lpbsa.listener

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import de.samthedev.lpbsa.access.AccessController

class ServerPreConnectListener(private val controller: AccessController) {
    @Subscribe(order = PostOrder.LATE)
    fun onServerPreConnect(event: ServerPreConnectEvent): EventTask =
        EventTask.resumeWhenComplete(controller.handle(event))

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        controller.clearCooldown(event.player.uniqueId)
    }
}
