package de.samthedev.lpbsa.luckperms

import com.velocitypowered.api.proxy.Player
import de.samthedev.lpbsa.access.PermissionSubject
import net.luckperms.api.LuckPerms
import net.luckperms.api.context.DefaultContextKeys
import net.luckperms.api.query.QueryMode
import java.util.Locale

class LuckPermsService(private val provider: () -> LuckPerms) {
    constructor(luckPerms: LuckPerms) : this({ luckPerms })

    fun subject(player: Player, targetServer: String): PermissionSubject {
        val luckPerms = provider()
        val user = luckPerms.userManager.getUser(player.uniqueId)
            ?: error("LuckPerms has no loaded user for online player ${player.username}")
        val activeQueryOptions = luckPerms.contextManager.getQueryOptions(player)
        val activeContexts = if (activeQueryOptions.mode() == QueryMode.CONTEXTUAL) {
            activeQueryOptions.context().toMap()
        } else {
            emptyMap()
        }
        val contexts = targetContexts(activeContexts, targetServer)
        val contextBuilder = luckPerms.contextManager.contextSetFactory.immutableBuilder()
        contexts.forEach { (key, values) -> values.forEach { value -> contextBuilder.add(key, value) } }
        val queryOptions = activeQueryOptions.toBuilder()
            .mode(QueryMode.CONTEXTUAL)
            .context(contextBuilder.build())
            .build()
        val permissionData = user.cachedData.getPermissionData(queryOptions)
        val groups = user.getInheritedGroups(queryOptions).map { it.name.lowercase(Locale.ROOT) }.toSet()
        return object : PermissionSubject {
            override val name: String = player.username

            override fun permissionValue(permission: String) = permissionData.checkPermission(permission)

            override fun isInGroup(group: String): Boolean = group.lowercase(Locale.ROOT) in groups
        }
    }

    fun version(): String = provider().pluginMetadata.version
}

internal fun targetContexts(
    active: Map<String, Set<String>>,
    targetServer: String,
): Map<String, Set<String>> = buildMap {
    active.forEach { (key, values) ->
        if (!key.equals(DefaultContextKeys.SERVER_KEY, ignoreCase = true)) put(key, values.toSet())
    }
    put(DefaultContextKeys.SERVER_KEY, setOf(targetServer.lowercase(Locale.ROOT)))
}
