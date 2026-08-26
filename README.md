# LPBSA

LPBSA (LuckPerms-Based Server Access) is a Velocity plugin that controls which backend servers a player may join.

It supports:

- LuckPerms permissions and inherited groups
- `ANY` and `ALL` access requirements
- Per-server and global bypass permissions
- Initial-login redirects and transfer denial
- Reusable access profiles
- Safe configuration reloads
- MiniMessage messages

## Requirements

- Velocity 3.5-compatible proxy
- Java 25
- LuckPerms 5.5-compatible Velocity plugin

Install LPBSA and LuckPerms on Velocity. Nothing needs to be installed on the backend servers.

## Installation

1. Stop Velocity.
2. Install LuckPerms on the proxy.
3. Copy `LPBSA-<version>.jar` into Velocity's `plugins` directory.
4. Start Velocity to create `plugins/lpbsa/config.yml` and `messages.yml`.
5. Edit `config.yml` and run `/lpbsa reload`.

## Quick setup

The default configuration does not restrict any server. To restrict a backend named `build`, use:

```yaml
servers:
  build:
    enabled: true
    requirements:
      mode: ANY
      permissions:
        - "lpbsa.server.build"
      groups: []
    bypass-permission: "lpbsa.bypass.build"
```

Grant access with LuckPerms:

```text
/lpv group builder permission set lpbsa.server.build true
```

Players with that permission may join `build`. Removing it takes effect on their next connection attempt.

## Access requirements

A server rule can check permissions, inherited groups, or both:

```yaml
servers:
  staff:
    enabled: true
    requirements:
      mode: ALL
      permissions:
        - "network.staff"
      groups:
        - "moderator"
```

- `ANY` allows access when at least one listed permission or group matches.
- `ALL` requires every listed permission and group.
- An enabled rule with empty requirements denies everyone except bypass users.
- Omitting `requirements` uses `lpbsa.server.<server>` automatically.

LuckPerms checks use the destination server context. For example, access to `build` is evaluated with `server=build`.

## Default policy

The `default-policy` setting controls servers without an explicit rule:

```yaml
settings:
  default-policy: OPEN
```

- `OPEN`: unconfigured servers are public.
- `RESTRICTED`: an unconfigured server requires `lpbsa.server.<server>`.

When using `RESTRICTED`, an explicit rule with `enabled: false` makes that server public.

## Denial behavior

```yaml
settings:
  fail-mode: CLOSED
  denial:
    transfer-action: STAY
    initial-action: REDIRECT
    fallback-server: "lobby"
    notification: CHAT
    message-cooldown-ms: 1000
```

| Setting | Values | Meaning |
| --- | --- | --- |
| `transfer-action` | `STAY`, `REDIRECT`, `DISCONNECT` | Action when a connected player is denied |
| `initial-action` | `REDIRECT`, `DISCONNECT` | Action when the player's first backend is denied |
| `fallback-server` | Velocity server name | Destination used by redirects |
| `notification` | `CHAT`, `ACTION_BAR`, `BOTH`, `NONE` | Where denial messages appear |
| `message-cooldown-ms` | `0` to `600000` | Delay between repeated messages |
| `fail-mode` | `CLOSED`, `OPEN` | Behavior when authorization fails internally |

Keep `fail-mode: CLOSED` for secure operation. A missing, self-referencing, or unauthorized fallback causes a safe disconnect.

A server can override denial settings:

```yaml
servers:
  staff:
    enabled: true
    permission: "network.staff"
    denial:
      initial-action: DISCONNECT
      transfer-action: STAY
      message: "server-access-denied"
```

## Reusable profiles

Profiles let several servers share the same requirements:

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

Do not set both `profile` and `requirements` on the same server.

## Permissions

| Permission | Purpose |
| --- | --- |
| `lpbsa.server.<server>` | Default permission for a backend |
| `lpbsa.bypass` | Bypass all server restrictions |
| `lpbsa.bypass.<server>` | Bypass one server restriction |
| `lpbsa.command` | Use `/lpbsa` |
| `lpbsa.command.help` | Use `/lpbsa help` |
| `lpbsa.command.reload` | Use `/lpbsa reload` |
| `lpbsa.command.status` | Use `/lpbsa status` |
| `lpbsa.command.servers` | Use `/lpbsa servers` |
| `lpbsa.command.check` | Use `/lpbsa check` |
| `lpbsa.command.test` | Use `/lpbsa test` |
| `lpbsa.command.version` | Use `/lpbsa version` |

## Commands

| Command | Description |
| --- | --- |
| `/lpbsa` | Show a short overview |
| `/lpbsa help` | List available commands |
| `/lpbsa reload` | Reload `config.yml` and `messages.yml` |
| `/lpbsa status` | Show the active configuration status |
| `/lpbsa servers` | List backend access policies |
| `/lpbsa check <player> <server>` | Check an online player's access |
| `/lpbsa test <server>` | Check your own access |
| `/lpbsa version` | Show version information |

An invalid reload is rejected, and the previous working configuration remains active.

## Messages

Edit `plugins/lpbsa/messages.yml` to change plugin messages. Messages use Adventure MiniMessage.

Common placeholders include:

- `<prefix>`
- `<player>`
- `<server>`
- `<current_server>`
- `<fallback>`
- `<reason>`

Placeholder values are inserted as plain text and cannot add MiniMessage actions or formatting.

## Forced hosts and plugin transfers

LPBSA checks backend connection attempts, not only `/server` commands. This includes forced hosts, plugin-issued transfers, and initial login routing.

```toml
[forced-hosts]
"build.example.com" = ["build"]
```

In this example, the player must satisfy the `build` rule before Velocity connects them to that backend.

## Security

LPBSA only protects traffic routed through Velocity. Do not expose backend ports directly to players.

- Bind local backends to `127.0.0.1` where practical.
- Firewall remote backends so only the proxy can reach them.
- Configure Velocity player-information forwarding securely.
- Keep LuckPerms and other routing plugins trusted and updated.

See [SECURITY.md](SECURITY.md) for the complete security boundary and reporting guidance.

## Troubleshooting

- Run `/lpbsa status` to inspect the active policy.
- Run `/lpbsa servers` to see each registered backend's policy.
- Use `/lpbsa test <server>` to test your own access.
- Check the proxy console after a failed reload.
- Enable `debug: true` temporarily for detailed access decisions.
- Enable `strict-server-validation: true` to reject unknown backend names.

## Building

```text
./gradlew clean build
```

The plugin JAR is created at `build/libs/LPBSA-<version>.jar`.

## License

LPBSA is licensed under the [MIT License](LICENSE).
