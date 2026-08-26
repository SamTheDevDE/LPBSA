package de.samthedev.lpbsa.luckperms

import com.velocitypowered.api.proxy.Player
import de.samthedev.lpbsa.access.PermissionSubject
import net.luckperms.api.LuckPerms

class LuckPermsService(private val luckPerms: LuckPerms) {
    fun subject(player: Player): PermissionSubject {
        val user = luckPerms.userManager.getUser(player.uniqueId)
            ?: error("LuckPerms has no loaded user for online player ${player.username}")
        val queryOptions = luckPerms.contextManager.getQueryOptions(player)
        val permissionData = user.cachedData.getPermissionData(queryOptions)
        val groups = user.getInheritedGroups(queryOptions).map { it.name.lowercase() }.toSet()
        return object : PermissionSubject {
            override val name: String = player.username

            override fun hasPermission(permission: String): Boolean =
                permissionData.checkPermission(permission).asBoolean()

            override fun isInGroup(group: String): Boolean = group.lowercase() in groups
        }
    }

    fun version(): String = luckPerms.pluginMetadata.version
}
