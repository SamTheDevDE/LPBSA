package de.samthedev.lpbsa.config

import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

class ConfigManager(
    private val dataDirectory: Path,
    private val registeredServers: () -> Set<String>,
    private val logger: Logger,
    private val loader: ConfigLoader = ConfigLoader(),
) {
    val configPath: Path = dataDirectory.resolve("config.yml")
    val messagesPath: Path = dataDirectory.resolve("messages.yml")

    fun initialize(): LoadedState {
        Files.createDirectories(dataDirectory)
        copyDefault("config.yml", configPath)
        copyDefault("messages.yml", messagesPath)
        return loader.load(configPath, messagesPath, registeredServers())
    }

    fun reload(store: RuntimeStateStore): Result<LoadedState> = runCatching {
        val loaded = loader.load(configPath, messagesPath, registeredServers())
        store.replace(loaded.state)
        loaded
    }.onSuccess { loaded ->
        loaded.warnings.forEach { warning -> logger.warn("[LPBSA] {}", warning) }
    }.onFailure { failure ->
        logger.error("[LPBSA] Reload rejected; the previous configuration remains active: {}", failure.message)
    }

    private fun copyDefault(resource: String, destination: Path) {
        if (Files.exists(destination)) return
        val input = javaClass.classLoader.getResourceAsStream(resource)
            ?: error("Missing bundled resource $resource")
        input.use { Files.copy(it, destination) }
    }
}
