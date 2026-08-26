package de.samthedev.lpbsa.config

class ConfigException(val problems: List<String>) : Exception(
    problems.joinToString(prefix = "Configuration validation failed:\n - ", separator = "\n - "),
)
