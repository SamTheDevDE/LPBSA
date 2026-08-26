package de.samthedev.lpbsa.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import de.samthedev.lpbsa.BuildInfo
import de.samthedev.lpbsa.access.AccessDecision
import de.samthedev.lpbsa.access.AccessEvaluator
import de.samthedev.lpbsa.access.summary
import de.samthedev.lpbsa.config.ConfigManager
import de.samthedev.lpbsa.config.RuntimeStateStore
import de.samthedev.lpbsa.luckperms.LuckPermsService
import de.samthedev.lpbsa.message.MessageService

class LPBSACommand(
    private val proxy: ProxyServer,
    private val states: RuntimeStateStore,
    private val configManager: ConfigManager,
    private val luckPerms: LuckPermsService,
    private val messages: MessageService,
    private val evaluator: AccessEvaluator = AccessEvaluator(),
) : SimpleCommand {
    private data class CommandInfo(val permission: String, val usage: String, val description: String)

    private val commands = linkedMapOf(
        "help" to CommandInfo("lpbsa.command.help", "lpbsa help", "Show commands available to you"),
        "reload" to CommandInfo("lpbsa.command.reload", "lpbsa reload", "Reload config.yml and messages.yml"),
        "status" to CommandInfo("lpbsa.command.status", "lpbsa status", "Show the active runtime configuration"),
        "servers" to CommandInfo("lpbsa.command.servers", "lpbsa servers", "List backend access policies"),
        "check" to CommandInfo("lpbsa.command.check", "lpbsa check <player> <server>", "Check an online player's access"),
        "test" to CommandInfo("lpbsa.command.test", "lpbsa test <server>", "Test your own access"),
        "version" to CommandInfo("lpbsa.command.version", "lpbsa version", "Show compatibility versions"),
    )

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (!source.hasPermission("lpbsa.command")) return deny(source)
        val arguments = invocation.arguments()
        if (arguments.isEmpty()) return messages.send(source, "overview")
        val name = arguments[0].lowercase()
        val info = commands[name] ?: return help(source)
        if (!source.hasPermission(info.permission)) return deny(source)
        when (name) {
            "help" -> help(source)
            "reload" -> reload(source)
            "status" -> status(source)
            "servers" -> servers(source)
            "check" -> check(source, arguments)
            "test" -> test(source, arguments)
            "version" -> version(source)
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val source = invocation.source()
        if (!source.hasPermission("lpbsa.command")) return emptyList()
        val args = invocation.arguments()
        if (args.size <= 1) {
            val prefix = args.firstOrNull()?.lowercase().orEmpty()
            return commands.filter { (name, info) -> name.startsWith(prefix) && source.hasPermission(info.permission) }.keys.toList()
        }
        val command = args[0].lowercase()
        return when {
            command == "check" && args.size == 2 -> matching(proxy.allPlayers.map(Player::getUsername), args[1])
            command == "check" && args.size == 3 -> matching(serverNames(), args[2])
            command == "test" && args.size == 2 -> matching(serverNames(), args[1])
            else -> emptyList()
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true

    private fun reload(source: CommandSource) {
        configManager.reload(states).fold(
            onSuccess = {
                it.warnings.forEach { warning -> messages.send(source, "check-detail", mapOf("reason" to warning)) }
                messages.send(source, "reload-success")
            },
            onFailure = { messages.send(source, "reload-failed") },
        )
    }

    private fun status(source: CommandSource) {
        val config = states.current().config
        messages.send(source, "status-header")
        val entries = linkedMapOf(
            "LPBSA version" to BuildInfo.VERSION,
            "Velocity API target" to BuildInfo.VELOCITY_API,
            "LuckPerms" to luckPerms.version(),
            "default policy" to config.defaultPolicy.name,
            "configured rules" to config.servers.size.toString(),
            "active restricted rules" to config.servers.values.count { it.enabled }.toString(),
            "fallback backend" to config.denial.fallbackServer,
            "fail mode" to config.failMode.name,
            "config version" to config.configVersion.toString(),
        )
        entries.forEach { (key, value) -> messages.send(source, "status-entry", mapOf("key" to key, "value" to value)) }
    }

    private fun servers(source: CommandSource) {
        val config = states.current().config
        messages.send(source, "servers-header")
        proxy.allServers.map { it.serverInfo.name }.sorted().forEach { name ->
            val restriction = config.restrictionFor(name)
            val state = if (restriction == null) "OPEN" else "RESTRICTED → ${restriction.requirements.entries.joinToString(" + ").ifEmpty { "NO REQUIREMENTS (DENY)" }}"
            messages.send(source, "server-entry", mapOf("server" to name, "state" to state))
        }
        config.unknownServers.sorted().forEach { name ->
            messages.send(source, "server-entry", mapOf("server" to name, "state" to "INVALID CONFIG"))
        }
    }

    private fun check(source: CommandSource, args: Array<String>) {
        if (args.size != 3) return usage(source, commands.getValue("check").usage)
        val player = proxy.getPlayer(args[1]).orElse(null)
            ?: return messages.send(source, "player-not-found", mapOf("player" to args[1]))
        evaluateAndSend(source, player, args[2], ownTest = false)
    }

    private fun test(source: CommandSource, args: Array<String>) {
        if (args.size != 2) return usage(source, commands.getValue("test").usage)
        val player = source as? Player ?: return messages.send(source, "player-only")
        evaluateAndSend(source, player, args[1], ownTest = true)
    }

    private fun evaluateAndSend(source: CommandSource, player: Player, server: String, ownTest: Boolean) {
        if (proxy.getServer(server).isEmpty) return messages.send(source, "unknown-server", mapOf("server" to server))
        val decision = evaluator.evaluate(states.current().config, server, luckPerms.subject(player))
        val key = when {
            ownTest && decision.allowed -> "test-allowed"
            ownTest -> "test-denied"
            decision.allowed -> "check-allowed"
            else -> "check-denied"
        }
        messages.send(source, key, mapOf("player" to player.username, "server" to server))
        messages.send(source, "check-detail", mapOf("reason" to decision.summary()))
    }

    private fun version(source: CommandSource) {
        messages.send(
            source,
            "version-info",
            mapOf(
                "version" to BuildInfo.VERSION,
                "velocity" to BuildInfo.VELOCITY_API,
                "luckperms" to BuildInfo.LUCKPERMS_API,
                "java" to Runtime.version().feature().toString(),
            ),
        )
    }

    private fun help(source: CommandSource) {
        messages.send(source, "help-header")
        commands.values.filter { source.hasPermission(it.permission) }.forEach {
            messages.send(source, "help-entry", mapOf("command" to it.usage, "description" to it.description))
        }
    }

    private fun deny(source: CommandSource) = messages.send(source, "no-permission")

    private fun usage(source: CommandSource, command: String) =
        messages.send(source, "invalid-usage", mapOf("command" to command))

    private fun serverNames(): List<String> = proxy.allServers.map { it.serverInfo.name }

    private fun matching(values: Collection<String>, prefix: String): List<String> =
        values.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
}
