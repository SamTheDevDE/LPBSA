# Changelog

All notable changes to LPBSA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- Evaluate permissions and inherited groups with the effective destination backend's LuckPerms server context.
- Run enforcement synchronously at the final Velocity event priority and preserve pre-existing denials and earlier redirects.
- Fail closed on startup configuration errors and keep reload state swaps atomic.
- Reject malformed security-sensitive YAML values instead of silently applying permissive defaults.
- Re-evaluate fallback authorization in the fallback backend context and prevent missing, self, or unauthorized redirects.
- Preserve LuckPerms `Tristate` semantics so explicit negation and undefined permissions cannot authorize access.

### Fixed

- Use `ServerPreConnectEvent.previousServer` to distinguish initial connections from transfers after kick/failover routing.
- Canonicalize Velocity backend names case-insensitively and reject duplicate rules that differ only by case.
- Remove backend ping network I/O from the connection event path.
- Restrict command suggestions by subcommand permission and make diagnostic checks target-context aware.
- Make message rendering failure-safe and release artifacts deterministic.
