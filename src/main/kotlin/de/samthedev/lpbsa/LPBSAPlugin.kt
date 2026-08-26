package de.samthedev.lpbsa

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import de.samthedev.lpbsa.access.AccessController
import de.samthedev.lpbsa.command.LPBSACommand
import de.samthedev.lpbsa.config.ConfigManager
import de.samthedev.lpbsa.config.RuntimeStateStore
import de.samthedev.lpbsa.listener.ServerPreConnectListener
import de.samthedev.lpbsa.luckperms.LuckPermsService
import de.samthedev.lpbsa.message.MessageService
import net.luckperms.api.LuckPermsProvider
import org.slf4j.Logger
import java.nio.file.Path

@Plugin(
    id = "lpbsa",
    name = "LPBSA",
    version = BuildInfo.VERSION,
    dependencies = [Dependency(id = "luckperms")],
)
class LPBSAPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        if (proxy.pluginManager.getPlugin("luckperms").isEmpty) {
            logger.error("[LPBSA] LuckPerms is required. LPBSA will not register access-control listeners.")
            return
        }
        try {
            val luckPerms = LuckPermsService(LuckPermsProvider.get())
            val configManager = ConfigManager(dataDirectory, ::registeredServers, logger)
            val loaded = configManager.initialize()
            loaded.warnings.forEach { logger.warn("[LPBSA] {}", it) }
            val states = RuntimeStateStore(loaded.state)
            val messages = MessageService(states)
            val controller = AccessController(proxy, states, luckPerms, messages, logger)

            proxy.eventManager.register(this, ServerPreConnectListener(controller))
            val command = LPBSACommand(proxy, states, configManager, luckPerms, messages)
            val metadata = proxy.commandManager.metaBuilder("lpbsa").plugin(this).build()
            proxy.commandManager.register(metadata, command)

            val active = loaded.state.config.servers.values.count { it.enabled }
            logger.info("[LPBSA] LuckPerms detected ({}).", luckPerms.version())
            logger.info("[LPBSA] Loaded {} active server rules.", active)
            logger.info("[LPBSA] LPBSA {} enabled.", BuildInfo.VERSION)
        } catch (failure: Exception) {
            logger.error("[LPBSA] Startup failed. Access-control listeners were not registered.", failure)
        }
    }

    private fun registeredServers(): Set<String> = proxy.allServers.map { it.serverInfo.name }.toSet()
}
