package de.samthedev.lpbsa.listener

import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse

class StartupFailClosedListenerTest {
    @Test
    fun `initial connections are denied when startup failed`() {
        val disconnects = AtomicInteger()
        val player = player(disconnects, AtomicInteger())
        val event = ServerPreConnectEvent(player, server("lobby"), null)

        StartupFailClosedListener().onServerPreConnect(event)

        assertFalse(event.result.isAllowed)
        kotlin.test.assertEquals(1, disconnects.get())
    }

    @Test
    fun `transfers are denied without disconnecting the existing backend`() {
        val disconnects = AtomicInteger()
        val messages = AtomicInteger()
        val player = player(disconnects, messages)
        val event = ServerPreConnectEvent(player, server("build"), server("lobby"))

        StartupFailClosedListener().onServerPreConnect(event)

        assertFalse(event.result.isAllowed)
        kotlin.test.assertEquals(0, disconnects.get())
        kotlin.test.assertEquals(1, messages.get())
    }

    private fun player(disconnects: AtomicInteger, messages: AtomicInteger): Player = proxy(Player::class.java) { method ->
        when (method.name) {
            "disconnect" -> disconnects.incrementAndGet().let { null }
            "sendMessage" -> messages.incrementAndGet().let { null }
            else -> defaultValue(method.returnType)
        }
    }

    private fun server(name: String): RegisteredServer = proxy(RegisteredServer::class.java) { method ->
        when (method.name) {
            "getServerInfo" -> ServerInfo(name, InetSocketAddress("127.0.0.1", 25565))
            else -> defaultValue(method.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, answer: (java.lang.reflect.Method) -> Any?): T =
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
