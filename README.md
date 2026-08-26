# LPBSA

LuckPerms-Based Server Access for Velocity.

LPBSA controls access to Velocity backend servers using the player's current LuckPerms permissions and inherited groups, evaluated with the destination backend's `server` context. LuckPerms remains the source of truth: LPBSA has no whitelist database, UUID grant list, or separate permission cache.

> [!IMPORTANT]
> LPBSA only protects connections routed through Velocity. Bind same-host backends to `127.0.0.1`, or firewall remote backend ports so only the proxy can reach them. Configure Velocity player-information forwarding correctly as well.

## Features

- Enforcement at `ServerPreConnectEvent`, covering `/server`, custom transfer commands, plugin transfers, and forced hosts
- Permission and inherited-group requirements with `ANY` and `ALL` modes
- Global and per-server bypass permissions
- Open-by-default or restricted-by-default policy
- Safe initial-connection redirects and configurable transfer denial behavior
- Reusable access profiles
- MiniMessage messages with safely escaped dynamic placeholders
- Atomic reloads: invalid files never replace the working runtime state
- No cached authorization results; permission changes apply on the next connection attempt

## Requirements

- Velocity API 3.5-compatible proxy
- Java 25
- LuckPerms 5.5-compatible Velocity installation

LPBSA is a Velocity plugin. Nothing needs to be installed on Paper, Folia, Spigot, or another backend implementation.

## Installation

1. Install LuckPerms on Velocity.
2. Place `LPBSA-<version>.jar` in Velocity's `plugins` directory.
3. Start the proxy once to create `plugins/lpbsa/config.yml` and `messages.yml`.
4. Edit the generated files and run `/lpbsa reload`.

## Quick Start

Restrict the Velocity backend named `build`:

```yaml
servers:
  build:
    enabled: true
    requirements:
      mode: ANY
      permissions:
        - "lpbsa.server.build"
      groups: []
```

Give a LuckPerms group the permission:

```text
/lpv group builder permission set lpbsa.server.build true
```

Adding Steve to that group immediately grants access:

```text
/lpv user Steve parent add builder
```

There is no LPBSA whitelist or grant command. Removing the group or permission affects Steve's next access attempt without a proxy restart or LPBSA reload.

## Configuration

The shipped configuration is open by default and includes a disabled `build` example. A complete rule can combine permissions and inherited groups:

```yaml
settings:
  default-policy: OPEN
  global-bypass-permission: "lpbsa.bypass"
  fail-mode: CLOSED
  strict-server-validation: false
  denial:
    transfer-action: STAY
    initial-action: REDIRECT
    fallback-server: "lobby"
    notification: CHAT
    message-cooldown-ms: 1000

servers:
  build:
    enabled: true
    requirements:
      mode: ANY
      permissions:
        - "lpbsa.server.build"
      groups:
        - "builder"
    bypass-permission: "lpbsa.bypass.build"
```

`ANY` requires one entry across both lists. `ALL` requires every entry. If a restricted rule explicitly contains no valid requirements, LPBSA warns and denies access. Omitting `requirements` entirely derives `lpbsa.server.<server>` automatically.

With `default-policy: RESTRICTED`, every unconfigured backend requires `lpbsa.server.<server>`. An explicit rule with `enabled: false` is an open exemption.

Reusable profiles are requirement sets:

```yaml
profiles:
  builders:
    requirements:
      mode: ANY
      permissions: ["network.builder"]
      groups: ["builder"]

servers:
  build:
    enabled: true
    profile: "builders"
```

Unknown profiles and malformed values reject a reload. Unknown backend names warn by default or reject the reload when `strict-server-validation` is enabled.

## Permissions

| Permission | Purpose |
| --- | --- |
| `lpbsa.server.<server>` | Default access permission for a backend |
| `lpbsa.bypass` | Bypass every LPBSA restriction |
| `lpbsa.bypass.<server>` | Bypass one backend's restriction |
| `lpbsa.command` | Use the base command |
| `lpbsa.command.help` | View command help |
| `lpbsa.command.reload` | Reload both configuration files |
| `lpbsa.command.status` | View runtime status |
| `lpbsa.command.servers` | List backend policies |
| `lpbsa.command.check` | Check an online player's access |
| `lpbsa.command.test` | Test the sender's access |
| `lpbsa.command.version` | View version information |

LuckPerms handles wildcard, inheritance, temporary, contextual, and negated permissions. LPBSA does not reimplement those rules.

## Commands

```text
/lpbsa
/lpbsa help
/lpbsa reload
/lpbsa status
/lpbsa servers
/lpbsa check <player> <server>
/lpbsa test <server>
/lpbsa version
```

Suggestions are limited to subcommands the sender may use. `/lpbsa check` intentionally supports online players only.

## Forced Hosts

Velocity can route a hostname directly to a restricted backend:

```toml
[forced-hosts]
"build.example.com" = ["build"]
```

```text
build.example.com
       ↓
Velocity selects build
       ↓
LPBSA evaluates build
       ↓
allowed / redirected / disconnected
```

LPBSA does not configure DNS. A missing, self-referential, or unauthorized fallback causes a safe disconnect instead of another redirect. LPBSA performs no backend ping on the event path; if a registered fallback is offline, Velocity's normal connection-failure handling applies.

## How Access Checking Works

LPBSA observes the effective `ServerResult` destination synchronously at Velocity's lowest event priority, after normal routing plugins. It leaves an existing denial untouched, ignores unrestricted destinations, and queries current LuckPerms data using a target-specific `server=<destination>` context for restricted destinations. Allowed results are not rewritten. Denied transfers normally leave the player on the backend reported by `ServerPreConnectEvent.previousServer`; denied initial connections redirect or disconnect according to configuration.

Plugins deliberately registered at the same final priority can still mutate an event after LPBSA, depending on registration order. Treat every plugin with backend-routing capability as part of the proxy's trusted computing base and audit it before installation.

`fail-mode` only handles unexpected authorization failures on restricted servers. It is separate from `default-policy`.

Startup configuration errors install an emergency listener that denies backend connections until Velocity is restarted with valid files. LuckPerms remains a required Velocity dependency; if it is absent, Velocity will refuse to load LPBSA.

## Examples

Require two permissions:

```yaml
requirements:
  mode: ALL
  permissions:
    - "network.staff"
    - "network.build-certified"
  groups: []
```

Override denial behavior for one server:

```yaml
denial:
  transfer-action: STAY
  initial-action: DISCONNECT
  message: "staff-only"
```

Add `staff-only` to `messages.yml` using MiniMessage before reloading.

## Backend Security

Do not expose a backend directly to players. LPBSA cannot inspect or block traffic that bypasses Velocity. On one machine, prefer loopback addresses such as `127.0.0.1:25566`. Across machines, firewall backend ports to the proxy host and use Velocity's documented secure forwarding configuration.

## Building

```text
./gradlew clean build
```

The distributable is written to `build/libs/LPBSA-<version>.jar`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security-sensitive reports should follow [SECURITY.md](SECURITY.md).

## License

LPBSA is available under the [MIT License](LICENSE).

LPBSA is an independent project and is not affiliated with or endorsed by LuckPerms.
