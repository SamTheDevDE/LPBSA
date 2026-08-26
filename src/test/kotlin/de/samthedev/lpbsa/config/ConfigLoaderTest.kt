package de.samthedev.lpbsa.config

import de.samthedev.lpbsa.message.MessageService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.slf4j.LoggerFactory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `bundled configuration loads`() {
        val loaded = load(resource("config.yml"), setOf("lobby", "build"))
        assertEquals(DefaultPolicy.OPEN, loaded.state.config.defaultPolicy)
        assertEquals(false, loaded.state.config.servers.getValue("build").enabled)
    }

    @Test
    fun `custom permission is parsed`() {
        val loaded = load(config(serverBody = """
            enabled: true
            requirements:
              mode: ANY
              permissions: [network.access.builder]
              groups: []
        """), setOf("lobby", "build"))
        assertEquals(listOf("network.access.builder"), loaded.state.config.servers.getValue("build").requirements.permissions)
    }

    @Test
    fun `missing requirements use derived permission`() {
        val loaded = load(config(serverBody = "enabled: true"), setOf("lobby", "build"))
        assertEquals(listOf("lpbsa.server.build"), loaded.state.config.servers.getValue("build").requirements.permissions)
    }

    @Test
    fun `reusable profile resolves into server rule`() {
        val text = config(
            profiles = """
              builders:
                requirements:
                  mode: ANY
                  permissions: [network.builder]
                  groups: [builder]
            """,
            serverBody = """
              enabled: true
              profile: builders
            """,
        )
        val loaded = load(text, setOf("lobby", "build"))
        val rule = loaded.state.config.servers.getValue("build")
        assertEquals("builders", rule.profile)
        assertEquals(listOf("builder"), rule.requirements.groups)
    }

    @Test
    fun `unknown profile fails validation`() {
        assertFailsWith<ConfigException> {
            load(config(serverBody = """
                enabled: true
                profile: missing
            """), setOf("lobby", "build"))
        }
    }

    @Test
    fun `invalid enum reports its path`() {
        val failure = assertFailsWith<ConfigException> {
            load(config(defaultPolicy = "SOME"), setOf("lobby", "build"))
        }
        assertTrue(failure.message.orEmpty().contains("settings.default-policy"))
    }

    @Test
    fun `server denial overrides inherit unspecified global values`() {
        val loaded = load(config(serverBody = """
            enabled: true
            denial:
              initial-action: DISCONNECT
        """), setOf("lobby", "build"))
        val denial = loaded.state.config.restrictionFor("build")!!.denial
        assertEquals(TransferAction.STAY, denial.transferAction)
        assertEquals(InitialAction.DISCONNECT, denial.initialAction)
        assertEquals("lobby", denial.fallbackServer)
    }

    @Test
    fun `strict validation rejects unknown backend`() {
        assertFailsWith<ConfigException> {
            load(config(strict = true), setOf("lobby"))
        }
    }

    @Test
    fun `failed candidate load does not replace active state`() {
        val managedDirectory = directory.resolve("managed")
        val manager = ConfigManager(managedDirectory, { setOf("lobby", "build") }, LoggerFactory.getLogger("reload-test"))
        val good = manager.initialize().state
        val store = RuntimeStateStore(good)
        manager.configPath.writeText(config(defaultPolicy = "INVALID"))
        assertTrue(manager.reload(store).isFailure)
        assertTrue(store.current() === good)
    }

    @Test
    fun `dynamic MiniMessage values are inserted literally`() {
        val state = load(config(), setOf("lobby", "build")).state
        val rendered = MessageService(RuntimeStateStore(state)).render(
            "server-access-denied",
            mapOf("server" to "<click:run_command:'/op Steve'>build</click>"),
        )
        val plain = PlainTextComponentSerializer.plainText().serialize(rendered)
        assertTrue(plain.contains("<click:run_command:'/op Steve'>build</click>"))
    }

    private fun load(config: String, registered: Set<String>): LoadedState {
        val configPath = directory.resolve("config-${System.nanoTime()}.yml")
        val messagesPath = directory.resolve("messages-${System.nanoTime()}.yml")
        configPath.writeText(config.trimIndent())
        messagesPath.writeText(resource("messages.yml"))
        return ConfigLoader().load(configPath, messagesPath, registered)
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)).bufferedReader().use { it.readText() }

    private fun config(
        defaultPolicy: String = "OPEN",
        strict: Boolean = false,
        profiles: String = "{}",
        serverBody: String = """
            enabled: false
            requirements:
              mode: ANY
              permissions: [lpbsa.server.build]
              groups: []
        """,
    ): String {
        val profilesValue = if (profiles.trim() == "{}") "{}" else "\n${profiles.trimIndent().prependIndent("  ")}"
        val serverValue = serverBody.trimIndent().prependIndent("    ")
        return """
            config-version: 1
            debug: false
            settings:
              default-policy: $defaultPolicy
              global-bypass-permission: lpbsa.bypass
              fail-mode: CLOSED
              strict-server-validation: $strict
              denial:
                transfer-action: STAY
                initial-action: REDIRECT
                fallback-server: lobby
                notification: CHAT
                message-cooldown-ms: 1000
              logging:
                denied-connections: true
                allowed-connections: false
            profiles: __PROFILES__
            servers:
              build:
            __SERVER__
        """.trimIndent()
            .replace("__PROFILES__", profilesValue)
            .replace("__SERVER__", serverValue)
    }
}
