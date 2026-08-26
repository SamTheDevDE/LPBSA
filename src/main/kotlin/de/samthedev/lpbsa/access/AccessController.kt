package de.samthedev.lpbsa.access

import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import de.samthedev.lpbsa.config.InitialAction
import de.samthedev.lpbsa.config.ResolvedRestriction
import de.samthedev.lpbsa.config.RuntimeStateStore
import de.samthedev.lpbsa.config.TransferAction
import de.samthedev.lpbsa.luckperms.LuckPermsService
import de.samthedev.lpbsa.message.MessageCooldown
import de.samthedev.lpbsa.message.MessageService
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture

class AccessController(
    private val proxy: ProxyServer,
    private val states: RuntimeStateStore,
    private val luckPerms: LuckPermsService,
    private val messages: MessageService,
    private val logger: Logger,
    private val evaluator: AccessEvaluator = AccessEvaluator(),
    private val fallbackPlanner: FallbackPlanner = FallbackPlanner(),
    private val cooldown: MessageCooldown = MessageCooldown(),
) {
    fun handle(event: ServerPreConnectEvent): CompletableFuture<Void> {
        if (!event.result.isAllowed) return completed()
        val target = event.result.server.orElse(event.originalServer)
        val targetName = target.serverInfo.name
        val state = states.current()
        val restriction = state.config.restrictionFor(targetName)
        if (restriction == null) {
            debug(event, targetName, null, AccessDecision.OpenServer, "ALLOW")
            return completed()
        }
        val subject = try {
            luckPerms.subject(event.player)
        } catch (failure: Exception) {
            val decision = AccessDecision.Failure(failure, state.config.failMode == de.samthedev.lpbsa.config.FailMode.OPEN)
            handleFailure(event, targetName, decision)
            return completed()
        }
        val decision = evaluator.evaluate(state.config, targetName, subject)

        if (decision is AccessDecision.Failure) {
            handleFailure(event, targetName, decision)
            return completed()
        }

        if (decision.allowed) {
            if (state.config.logging.allowedConnections) {
                logger.info("[LPBSA] Allowed {} -> {} ({})", event.player.username, targetName, decision.summary())
            }
            debug(event, targetName, restriction, decision, "ALLOW")
            return completed()
        }

        if (state.config.logging.deniedConnections) {
            logger.info("[LPBSA] Denied {} -> {} ({})", event.player.username, targetName, decision.summary())
        }

        val current = event.player.currentServer.map { it.serverInfo.name }.orElse("")
        val initial = current.isEmpty()
        if (initial) {
            return when (restriction.denial.initialAction) {
                InitialAction.REDIRECT -> redirectOrDisconnect(event, restriction, subject, current, decision)
                InitialAction.DISCONNECT -> {
                    disconnect(event, restriction.denial.message, targetName, current)
                    debug(event, targetName, restriction, decision, restriction.denial.initialAction.name)
                    completed()
                }
            }
        }
        return when (restriction.denial.transferAction) {
            TransferAction.STAY -> {
                event.result = ServerPreConnectEvent.ServerResult.denied()
                notify(event, restriction, targetName, current, restriction.denial.message)
                debug(event, targetName, restriction, decision, restriction.denial.transferAction.name)
                completed()
            }
            TransferAction.REDIRECT -> redirectOrDisconnect(event, restriction, subject, current, decision)
            TransferAction.DISCONNECT -> {
                disconnect(event, restriction.denial.message, targetName, current)
                debug(event, targetName, restriction, decision, restriction.denial.transferAction.name)
                completed()
            }
        }
    }

    fun clearCooldown(playerId: java.util.UUID) = cooldown.remove(playerId)

    private fun handleFailure(event: ServerPreConnectEvent, target: String, decision: AccessDecision.Failure) {
        if (decision.failOpen) {
            logger.error("[LPBSA] Authorization failed for {} -> {}; allowing because fail-mode is OPEN.", event.player.username, target, decision.cause)
            debug(event, target, states.current().config.restrictionFor(target), decision, "ALLOW_FAIL_OPEN")
        } else {
            logger.error("[LPBSA] Authorization failed for {} -> {}; denying because fail-mode is CLOSED.", event.player.username, target, decision.cause)
            event.result = ServerPreConnectEvent.ServerResult.denied()
            event.player.disconnect(messages.render("fallback-unavailable", mapOf("player" to event.player.username, "server" to target)))
            debug(event, target, states.current().config.restrictionFor(target), decision, "DISCONNECT_FAIL_CLOSED")
        }
    }

    private fun redirectOrDisconnect(
        event: ServerPreConnectEvent,
        restriction: ResolvedRestriction,
        subject: PermissionSubject,
        current: String,
        deniedDecision: AccessDecision,
    ): CompletableFuture<Void> {
        val fallbackName = restriction.denial.fallbackServer
        val registered = proxy.allServers.associateBy { it.serverInfo.name }
        val fallbackDecision = registered[fallbackName]?.let {
            evaluator.evaluate(states.current().config, fallbackName, subject)
        }
        return when (val plan = fallbackPlanner.plan(restriction.server, fallbackName, registered.keys, fallbackDecision)) {
            is FallbackPlan.Redirect -> {
                val fallback = registered.getValue(plan.server)
                val completion = CompletableFuture<Void>()
                fallback.ping().whenComplete { _, pingFailure ->
                    try {
                        if (pingFailure == null) {
                            event.result = ServerPreConnectEvent.ServerResult.allowed(fallback)
                            notify(event, restriction, restriction.server, current, "redirecting", fallbackName)
                            debug(event, restriction.server, restriction, deniedDecision, "REDIRECT")
                        } else {
                            logger.warn(
                                "[LPBSA] Fallback {} is unavailable for {}: {}",
                                fallbackName,
                                event.player.username,
                                pingFailure.message ?: pingFailure.javaClass.simpleName,
                            )
                            disconnect(event, "fallback-unavailable", restriction.server, current, fallbackName)
                            debug(event, restriction.server, restriction, deniedDecision, "DISCONNECT_FALLBACK_UNAVAILABLE")
                        }
                    } catch (failure: Exception) {
                        logger.error("[LPBSA] Failed while resolving fallback {} for {}.", fallbackName, event.player.username, failure)
                        event.result = ServerPreConnectEvent.ServerResult.denied()
                        event.player.disconnect(messages.render("fallback-unavailable", placeholders(event, restriction.server, current, fallbackName)))
                    } finally {
                        completion.complete(null)
                    }
                }
                completion
            }
            is FallbackPlan.Disconnect -> {
                logger.warn("[LPBSA] Cannot redirect {} from {} to {}: {}", event.player.username, restriction.server, fallbackName, plan.reason)
                disconnect(event, "fallback-unavailable", restriction.server, current, fallbackName)
                debug(event, restriction.server, restriction, deniedDecision, "DISCONNECT_UNSAFE_FALLBACK")
                completed()
            }
        }
    }

    private fun completed(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    private fun disconnect(
        event: ServerPreConnectEvent,
        messageKey: String,
        server: String,
        current: String,
        fallback: String = "",
    ) {
        event.result = ServerPreConnectEvent.ServerResult.denied()
        event.player.disconnect(messages.render(messageKey, placeholders(event, server, current, fallback)))
    }

    private fun notify(
        event: ServerPreConnectEvent,
        restriction: ResolvedRestriction,
        server: String,
        current: String,
        messageKey: String,
        fallback: String = "",
    ) {
        if (!cooldown.shouldNotify(event.player.uniqueId, server, restriction.denial.messageCooldownMs)) return
        messages.notify(
            event.player,
            restriction.denial.notification,
            messageKey,
            placeholders(event, server, current, fallback),
        )
    }

    private fun placeholders(
        event: ServerPreConnectEvent,
        server: String,
        current: String,
        fallback: String,
    ) = mapOf(
        "player" to event.player.username,
        "server" to server,
        "current_server" to current,
        "fallback" to fallback,
    )

    private fun debug(
        event: ServerPreConnectEvent,
        target: String,
        restriction: ResolvedRestriction?,
        decision: AccessDecision,
        action: String,
    ) {
        if (!states.current().config.debug) return
        logger.info(
            "[LPBSA] DEBUG player={} current={} target={} rule={} profile={} requirements={} decision={} action={} fallback={}",
            event.player.username,
            event.player.currentServer.map { it.serverInfo.name }.orElse("<initial>"),
            target,
            if (restriction?.explicit == true) "explicit" else if (restriction == null) "open" else "default-restricted",
            restriction?.profile ?: "<none>",
            restriction?.requirements?.entries?.joinToString() ?: "<none>",
            decision.summary(),
            action,
            restriction?.denial?.fallbackServer ?: "<none>",
        )
    }
}
