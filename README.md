# ZenithProxy Manager

A hard fork of [ZenithProxy](https://github.com/rfresh2/ZenithProxy) that runs many Minecraft accounts
from one Docker container and one web UI.

ZenithProxy is a Minecraft bot that players can also log into and control in-game. It keeps accounts
online around the clock and lets you share them with friends without sharing passwords. It was built
for [2b2t.org](https://www.2b2t.org/) but works on any server.

This fork wraps it in a manager. You never edit config files or type commands: adding an account,
connecting it to a server, sharing it and changing settings all happen in the browser.

## What the fork adds

- **Web UI** to add accounts (Microsoft device-code login with one click, auth-cache import, or
  offline), add servers, and connect, disconnect or watch every bot. Works on phones.
- **Instances:** each instance is an account on a server. One account can be on several servers at once.
- **Clean settings:** curated pages instead of commands, layered Global → Server → Instance, with
  search, a "changed" filter and reset to inherited. Settings apply live.
- **One Minecraft port with a lobby:** players join a single address and get a chest menu of the bots
  they may use. Click to play, right-click to spectate, `/lobby` to go back. Clients from 1.20.5 to
  the newest version ViaVersion supports can join.
- **Users and sharing:** one admin, users with their own accounts and private servers, and sharing
  by Minecraft name or Discord user with role presets. One permission system covers the web, the
  lobby and Discord.
- **Optional central Discord bot:** a single bot token for everything. It creates a category per
  account and a channel per instance, posts Zenith's notification embeds and pings the owner. It
  has `/status`, `/connect` and `/command`, and every notification can be switched off. There is no
  chat relay.
- **Per-instance outbound proxy** (SOCKS/HTTP).
- Instances return to their previous state after a restart and are restarted with backoff if they crash.

Deleted compared to upstream: Zenith's own per-instance Discord bot and its Discord commands, the
Discord chat relay, the interactive terminal, the self-updater and its commands, the Python
launcher, the VPS install scripts, the release workflows and the setup guides in the wiki. Updates
come from new Docker images.

## Running it

```sh
git clone https://github.com/jkramer5103/ZenithProxy.git
cd ZenithProxy
docker compose up -d --build
```

Open `http://<host>:8080`. The first visit creates the admin account. Point Minecraft clients at
`<host>:25565`.

All data (state, accounts, instance folders, secrets) lives in `./data`. Serve the web UI over HTTPS
by putting it behind your own reverse proxy.

The image builds Zenith as a GraalVM native binary for x86-64-v3 CPUs by default. On ARM or older
CPUs, set the build argument `ZENITH_RUNTIME: jar` in `docker-compose.yml`. Zenith then runs on the
JVM and uses more memory per instance.

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `ZENITH_DATA_DIR` | `/data` | State, accounts, instances and secrets |
| `ZENITH_WEB_PORT` / `ZENITH_WEB_BIND` | `8080` / `0.0.0.0` | Web UI and API |
| `ZENITH_LOBBY_PORT` | `25565` | Public Minecraft port (lobby and router) |
| `ZENITH_INSTANCE_PORT_BASE` / `ZENITH_CONTROL_PORT_BASE` | `30000` / `40000` | Localhost ports for instances |
| `ZENITH_LOBBY_ONLINE_MODE` | `true` | Set to `false` only for local testing |

## How it works

```
browser ──► manager (Java)  ── web UI + REST/WebSocket API, users and grants,
 :8080        │                accounts, supervisor, lobby + router, Discord bot
MC client ──► │ localhost control API
 :25565       ▼
            zenith × N  ── one process per instance, in /data/instances/<id>
```

- `manager/`: the manager, a Gradle subproject (Java 25, Javalin, MCProtocolLib, ViaVersion).
- `web/`: the UI (SvelteKit static build, Tailwind), served by the manager.
- `src/`: ZenithProxy itself. With `ZENITH_MANAGED=1` it runs in managed mode
  (`com.zenith.control`): a localhost control API and no terminal, Discord bot or updater.

The full design is in [docs/revamp/PLAN.md](docs/revamp/PLAN.md).

## Development

Gradle installs the required Java version (Java 25).

```sh
./gradlew :shadowJar :manager:shadowJar   # build/libs/ZenithProxy.jar, manager/build/libs/zenith-manager.jar
./gradlew :manager:test
cd web && npm ci && npm run build          # web/build
cd web && npm run check                    # type-check the UI
```

To run the manager locally, point it at the jar and the built UI:

```sh
ZENITH_DATA_DIR=./data ZENITH_WEB_DIR=web/build ZENITH_LOBBY_ONLINE_MODE=false \
ZENITH_COMMAND="java -jar build/libs/ZenithProxy.jar" \
  java -jar manager/build/libs/zenith-manager.jar
```

## Credits and license

All of the bot itself comes from [rfresh2/ZenithProxy](https://github.com/rfresh2/ZenithProxy) and its
contributors. The upstream [wiki](https://wiki.2b2t.vc/) documents what each module does.

Licensed under the [AGPL-3.0](LICENSE), like upstream. See [README-LICENSE.md](README-LICENSE.md).
