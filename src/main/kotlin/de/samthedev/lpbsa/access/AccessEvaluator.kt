package de.samthedev.lpbsa.access

import de.samthedev.lpbsa.config.FailMode
import de.samthedev.lpbsa.config.RequirementMode
import de.samthedev.lpbsa.config.Requirements
import de.samthedev.lpbsa.config.ResolvedRestriction
import de.samthedev.lpbsa.config.RuntimeConfig

interface PermissionSubject {
    val name: String
    fun hasPermission(permission: String): Boolean
    fun isInGroup(group: String): Boolean
}

class AccessEvaluator {
    fun evaluate(config: RuntimeConfig, server: String, subject: PermissionSubject): AccessDecision {
        val restriction = config.restrictionFor(server) ?: return AccessDecision.OpenServer
        return try {
            evaluateRestricted(config, restriction, subject)
        } catch (failure: Exception) {
            AccessDecision.Failure(failure, config.failMode == FailMode.OPEN)
        }
    }

    private fun evaluateRestricted(
        config: RuntimeConfig,
        restriction: ResolvedRestriction,
        subject: PermissionSubject,
    ): AccessDecision {
        if (subject.hasPermission(config.globalBypassPermission)) {
            return AccessDecision.AllowedByGlobalBypass(config.globalBypassPermission)
        }
        if (subject.hasPermission(restriction.bypassPermission)) {
            return AccessDecision.AllowedByServerBypass(restriction.bypassPermission)
        }
        return evaluateRequirements(restriction.requirements, subject)
    }

    private fun evaluateRequirements(requirements: Requirements, subject: PermissionSubject): AccessDecision {
        val checks = buildList {
            requirements.permissions.forEach { permission ->
                add(permission to subject.hasPermission(permission))
            }
            requirements.groups.forEach { group ->
                add("group:$group" to subject.isInGroup(group))
            }
        }
        if (checks.isEmpty()) return AccessDecision.Denied(DenialReason.EmptyRequirements)

        val matched = checks.filter { it.second }.map { it.first }
        val missing = checks.filterNot { it.second }.map { it.first }
        val allowed = when (requirements.mode) {
            RequirementMode.ANY -> matched.isNotEmpty()
            RequirementMode.ALL -> missing.isEmpty()
        }
        return if (allowed) {
            AccessDecision.AllowedByRequirement(matched, requirements.mode)
        } else {
            AccessDecision.Denied(DenialReason.RequirementsNotMet(requirements.mode, matched, missing))
        }
    }
}
