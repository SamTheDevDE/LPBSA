package de.samthedev.lpbsa.access

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import de.samthedev.lpbsa.config.DefaultPolicy
import de.samthedev.lpbsa.config.DenialSettings
import de.samthedev.lpbsa.config.FailMode
import de.samthedev.lpbsa.config.LoggingSettings
import de.samthedev.lpbsa.config.RequirementMode
import de.samthedev.lpbsa.config.Requirements
import de.samthedev.lpbsa.config.RuntimeConfig
import de.samthedev.lpbsa.config.ServerRule
import de.samthedev.lpbsa.listener.ServerPreConnectListener
import net.luckperms.api.util.Tristate
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionTargetResolverTest {
    @Test
    fun `redirect from an earlier plugin authorizes resulting server`() {
        val survival = server("survival")
        val build = server("build")
        val event = ServerPreConnectEvent(proxy(Player::class.java), survival, null)

        event.result = ServerPreConnectEvent.ServerResult.allowed(build)

        assertSame(build, ConnectionTargetResolver.effectiveServer(event))
        assertSame(survival, event.originalServer)
    }

    @Test
    fun `previous deny is never converted to an allow target`() {
        val event = ServerPreConnectEvent(proxy(Player::class.java), server("build"), null)
        event.result = ServerPreConnectEvent.ServerResult.denied()

        assertNull(ConnectionTargetResolver.effectiveServer(event))
    }

    @Test
    fun `forced-host initial destination is the effective target`() {
        val build = server("build")
        val event = ServerPreConnectEvent(proxy(Player::class.java), build, null)
        val target = ConnectionTargetResolver.effectiveServer(event)!!
        val config = restrictedBuildConfig()

        assertSame(build, target)
        assertNull(event.previousServer)
        assertFalse(AccessEvaluator().evaluate(config, target.serverInfo.name, subject()).allowed)
        assertTrue(AccessEvaluator().evaluate(config, target.serverInfo.name, subject("lpbsa.server.build")).allowed)
    }

    @Test
    fun `authorization listener runs synchronously at the final priority`() {
        val method = ServerPreConnectListener::class.java.getDeclaredMethod("onServerPreConnect", ServerPreConnectEvent::class.java)
        val annotation = method.getAnnotation(Subscribe::class.java)

        assertEquals(Short.MIN_VALUE, annotation.priority)
        assertEquals(false, annotation.async)
    }

    private fun server(name: String): RegisteredServer = proxy(RegisteredServer::class.java) { method ->
        when (method.name) {
            "getServerInfo" -> ServerInfo(name, InetSocketAddress("127.0.0.1", 25565))
            "getPlayersConnected" -> emptyList<Player>()
            else -> defaultValue(method.returnType)
        }
    }

    private fun restrictedBuildConfig() = RuntimeConfig(
        1, false, DefaultPolicy.OPEN, "lpbsa.bypass", FailMode.CLOSED, false,
        DenialSettings(), LoggingSettings(), emptyMap(),
        mapOf("build" to ServerRule("build", true, Requirements(RequirementMode.ANY, listOf("lpbsa.server.build"), emptyList()))),
        emptySet(),
    )

    private fun subject(vararg permissions: String) = object : PermissionSubject {
        override val name = "Steve"
        override fun permissionValue(permission: String): Tristate =
            if (permission in permissions) Tristate.TRUE else Tristate.UNDEFINED
        override fun isInGroup(group: String) = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, answer: (java.lang.reflect.Method) -> Any? = { defaultValue(it.returnType) }): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
            when (method.name) {
                "equals" -> instance === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "test-${type.simpleName}"
                else -> answer(method)
            }
        } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
