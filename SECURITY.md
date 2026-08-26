# Security Policy

Access-control bypasses, unsafe default behavior, MiniMessage injection, and fallback loops should be treated as security issues.

Do not publish vulnerability details in a public issue. Before public release, maintainers should enable GitHub private vulnerability reporting or document another private security contact here. Once private reporting is configured, submit a concise reproduction, affected versions, expected security boundary, and any proposed mitigation through that channel.

Never include Velocity forwarding secrets, database credentials, API keys, tokens, or private player data in a report.

## Security boundary

LPBSA enforces only connections routed through its Velocity instance. Backend ports must be firewalled or bound so clients cannot connect directly. LuckPerms, Velocity, and installed plugins able to change `ServerPreConnectEvent` at LPBSA's final priority are trusted components; a malicious routing plugin can bypass in-process policy enforcement.

Invalid startup configuration activates an emergency fail-closed connection listener. A rejected runtime reload keeps the complete previous configuration and message snapshot. The default `fail-mode: CLOSED` denies unexpected LuckPerms or authorization failures; changing it to `OPEN` explicitly accepts availability over confidentiality.
