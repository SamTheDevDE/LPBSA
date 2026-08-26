package de.samthedev.lpbsa.access

import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import de.samthedev.lpbsa.config.InitialAction
import de.samthedev.lpbsa.config.FailMode
import de.samthedev.lpbsa.config.ResolvedRestriction
import de.samthedev.lpbsa.config.RuntimeState
import de.samthedev.lpbsa.config.RuntimeStateStore
import de.samthedev.lpbsa.config.TransferAction
import de.samthedev.lpbsa.luckperms.LuckPermsService
import de.samthedev.lpbsa.message.MessageCooldown
import de.samthedev.lpbsa.message.MessageService
import org.slf4j.Logger

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
    fun handle(event: ServerPreConnectEvent) {
        val target = ConnectionTargetResolver.effectiveServer(event) ?: return
        val targetName = target.serverInfo.name
        val state = states.current()
        val restriction = state.config.restrictionFor(targetName)
        if (restriction == null) {
            debug(event, state, targetName, null, AccessDecision.OpenServer, "ALLOW")
            return
        }
        val subject = try {
            luckPerms.subject(event.player, targetName)
        } catch (failure: Exception) {
            val decision = AccessDecision.Failure(failure, state.config.failMode == FailMode.OPEN)
            handleFailure(event, state, targetName, decision)
            return
        }
        val decision = evaluator.evaluate(state.config, targetName, subject)

        if (decision is AccessDecision.Failure) {
            handleFailure(event, state, targetName, decision)
            return
        }

        if (decision.allowed) {
            if (state.config.logging.allowedConnections) {
                logger.info("[LPBSA] Allowed {} -> {} ({})", event.player.username, targetName, decision.summary())
            }
            debug(event, state, targetName, restriction, decision, "ALLOW")
            return
        }

        if (state.config.logging.deniedConnections) {
            logger.info("[LPBSA] Denied {} -> {} ({})", event.player.username, targetName, decision.summary())
        }

        val current = event.previousServer?.serverInfo?.name.orEmpty()
        val initial = current.isEmpty()
        if (initial) {
            when (restriction.denial.initialAction) {
                InitialAction.REDIRECT -> redirectOrDisconnect(event, state, restriction, current, decision)
                InitialAction.DISCONNECT -> {
                    disconnect(event, state, restriction.denial.message, targetName, current)
                    debug(event, state, targetName, restriction, decision, restriction.denial.initialAction.name)
                }
            }
            return
        }
        when (restriction.denial.transferAction) {
            TransferAction.STAY -> {
                event.result = ServerPreConnectEvent.ServerResult.denied()
                notify(event, state, restriction, targetName, current, restriction.denial.message)
                debug(event, state, targetName, restriction, decision, restriction.denial.transferAction.name)
            }
            TransferAction.REDIRECT -> redirectOrDisconnect(event, state, restriction, current, decision)
            TransferAction.DISCONNECT -> {
                disconnect(event, state, restriction.denial.message, targetName, current)
                debug(event, state, targetName, restriction, decision, restriction.denial.transferAction.name)
            }
        }
    }

    fun clearCooldown(playerId: java.util.UUID) = cooldown.remove(playerId)

    private fun handleFailure(
        event: ServerPreConnectEvent,
        state: RuntimeState,
        target: String,
        decision: AccessDecision.Failure,
    ) {
        if (decision.failOpen) {
            logger.error("[LPBSA] Authorization failed for {} -> {}; allowing because fail-mode is OPEN.", event.player.username, target, decision.cause)
            debug(event, state, target, state.config.restrictionFor(target), decision, "ALLOW_FAIL_OPEN")
        } else {
            logger.error("[LPBSA] Authorization failed for {} -> {}; denying because fail-mode is CLOSED.", event.player.username, target, decision.cause)
            event.result = ServerPreConnectEvent.ServerResult.denied()
            event.player.disconnect(messages.render(state.messages, "fallback-unavailable", mapOf("player" to event.player.username, "server" to target)))
            debug(event, state, target, state.config.restrictionFor(target), decision, "DISCONNECT_FAIL_CLOSED")
        }
    }

    private fun redirectOrDisconnect(
        event: ServerPreConnectEvent,
        state: RuntimeState,
        restriction: ResolvedRestriction,
        current: String,
        deniedDecision: AccessDecision,
    ) {
        val fallbackName = restriction.denial.fallbackServer
        val fallback = proxy.getServer(fallbackName).orElse(null)
        val registered = proxy.allServers.map { it.serverInfo.name }.toSet()
        val fallbackDecision = fallback?.let {
            try {
                evaluator.evaluate(state.config, it.serverInfo.name, luckPerms.subject(event.player, it.serverInfo.name))
            } catch (failure: Exception) {
                AccessDecision.Failure(failure, state.config.failMode == FailMode.OPEN)
            }
        }
        when (val plan = fallbackPlanner.plan(restriction.server, fallbackName, registered, fallbackDecision)) {
            is FallbackPlan.Redirect -> {
                if (fallback == null) {
                    disconnect(event, state, "fallback-unavailable", restriction.server, current, fallbackName)
                    return
                }
                event.result = ServerPreConnectEvent.ServerResult.allowed(fallback)
                notify(event, state, restriction, restriction.server, current, "redirecting", fallbackName)
                debug(event, state, restriction.server, restriction, deniedDecision, "REDIRECT")
            }
            is FallbackPlan.Disconnect -> {
                logger.warn("[LPBSA] Cannot redirect {} from {} to {}: {}", event.player.username, restriction.server, fallbackName, plan.reason)
                disconnect(event, state, "fallback-unavailable", restriction.server, current, fallbackName)
                debug(event, state, restriction.server, restriction, deniedDecision, "DISCONNECT_UNSAFE_FALLBACK")
            }
        }
    }

    private fun disconnect(
        event: ServerPreConnectEvent,
        state: RuntimeState,
        messageKey: String,
        server: String,
        current: String,
        fallback: String = "",
    ) {
        event.result = ServerPreConnectEvent.ServerResult.denied()
        event.player.disconnect(messages.render(state.messages, messageKey, placeholders(event, server, current, fallback)))
    }

    private fun notify(
        event: ServerPreConnectEvent,
        state: RuntimeState,
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
            state.messages,
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
        state: RuntimeState,
        target: String,
        restriction: ResolvedRestriction?,
        decision: AccessDecision,
        action: String,
    ) {
        if (!state.config.debug) return
        logger.info(
            "[LPBSA] DEBUG player={} current={} target={} rule={} profile={} requirements={} decision={} action={} fallback={}",
            event.player.username,
            event.previousServer?.serverInfo?.name ?: "<initial>",
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
