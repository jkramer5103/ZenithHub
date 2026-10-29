# ZenithHub: Multi-Account Web UI (hard fork of ZenithProxy)

Status: design settled 2026-10-28. This document is the source of truth for the revamp. Renamed
from ZenithProxy to ZenithHub on 2026-10-29: user-facing names only, code keeps `com.zenith` and `ZENITH_*`.

## Goals

- Run everything with one `docker compose up`: a single container and a single data volume.
- Manage many Minecraft accounts from a web UI. Adding an account is a single device-code click.
- Let one account run on several servers at the same time. Each (account, server) pair is an **instance**.
- Give players one public Minecraft port with an in-game lobby: a chest GUI of the instances you may use; click to join, `/lobby` to return.
- Offer an optional central Discord bot. Discord is never required.

## Architecture

```
            ┌──────────────────────── container ─────────────────────────┐
 browser ──►│ manager (Java)                                             │
  :8080     │  ├─ web UI (SvelteKit static) + REST/WebSocket API         │
            │  ├─ auth: admin + users + grants (one permission system)   │
            │  ├─ account service: device code, import, offline, refresh │
            │  ├─ supervisor: 1 Zenith process per instance              │
            │  ├─ lobby + router on the public MC port                   │
            │  └─ central Discord bot (optional)                         │
 MC client ►│       │ localhost control API + stdout                     │
  :25565    │       ▼                                                    │
            │ zenith (linux native) × N   ── each in /data/instances/<id>│
            └────────────────────────────────────────────────────────────┘
```

- **Manager**: a Gradle subproject (`manager/`) in the same repo, written in Java 25. It owns all state in `/data`.
- **Local UI preview**: Vite's `/api` proxy preserves the browser's Host header, including WebSocket upgrades, so the manager's same-origin check accepts live updates. For LAN browsing, the built UI (`npm run preview -- --host 0.0.0.0`) avoids the large development module graph and its slow first render.
- **Zenith instances**: unmodified behaviour apart from **managed mode** (`ZENITH_MANAGED=1`):
  - a localhost HTTP control API (status, commands, config defaults), authenticated with a per-process token;
  - no interactive terminal, no self-updater and no per-instance Discord bot;
  - logs go to stdout, where the manager captures and streams them;
  - no device-code prompts inside instances: the manager owns MSA login, refreshes tokens and syncs `mc_auth_cache.json`.
- **Updates**: Docker image tags only. CI (`.github/workflows/ci.yml`) publishes `ghcr.io/jkramer5103/zenithhub` (`latest`, `sha-<commit>`) from `main`. The image builds while the tests run, and `latest` only moves once they pass. Docs-only changes skip CI. There is no self-update.
- **No-cheats switch**: `ZENITH_DISABLE_CHEATS=true` on the container turns off KillAura, SpawnPatrol, TickRate, AutoTotem, AutoArmor, AutoMend, Spook, AntiAFK, AutoFish, AutoEat, AutoOmen, AutoDrop, AutoRespawn, Spammer and AutoReply (decided 2026-10-29; Pathfinder, PearlLoader, AutoDisconnect, VisualRange, Tasks and spectating stay). Zenith (`com.zenith.control.Cheats`) refuses to enable those modules and drops their commands; the manager (`instances/Cheats`) forces them off, hides their settings and rejects writes, keeping existing overrides for when the switch is removed. The settings editor shows a note.
- **Reconnect randomness** (fork change in Zenith, 2026-10-29): `client.extra.autoReconnect.randomSeconds` makes each auto reconnect wait `delaySeconds ± random`, and `client.maxPlaytimeReconnectRandomMins` makes each scheduled-reconnect session last `maxPlaytimeReconnectMins ± random`, re-rolled per connection and when the settings change. Both default to 0, which keeps upstream behaviour. They are under Settings → Connection, and `autoReconnect random <seconds>` is the command. Reconnects started by Active hours or the reconnect command keep their fixed delays.

## Data model (`/data/manager/state.json`)

| Entity   | Fields |
|----------|--------|
| User     | id, username, password hash (PBKDF2), admin flag, linked MC names, linked Discord ID |
| Account  | id, type (`msa` / `offline`), MC username + UUID; auth cache at `/data/accounts/<id>/mc_auth_cache.json` (0600) |
| Server   | id, name, address, port, settings overrides |
| Instance | id, name, accountId, serverId, settings overrides, lifecycle (`desiredRunning`, autostart policy), grants |
| Grant    | subject (`user:<id>` / `mc:<name>` / `discord:<id>`), role (Viewer, Player, Spectator, Operator, Admin), extra capabilities |

The admin is a superuser: they see every instance, can join any of them in-game, and have every right.

### Settings layering

`Zenith defaults → Global → Server → Instance → forced managed values`

- Zenith writes its defaults (`config.defaults.json`) on start in managed mode.
- Settings changed inside an instance (by commands or in-game) are diffed against `defaults⊕global⊕server` when it stops, and saved as instance overrides. In-game changes therefore persist and are never clobbered.
- Some values are always forced by the manager: bind address and port, target server, account, and turning off Discord, the terminal and the updater.

## Phases

### Phase 1: Foundation (done)
- [x] Zenith managed mode + control API (`com.zenith.control`)
- [x] Manager: state store, settings layering, supervisor (crash restart with backoff, restore previous state on boot)
- [x] Accounts: MSA device code (shown in the UI), auth-cache import, offline; background token refresh
- [x] Ownership: every user adds their own accounts and private servers; owners have full access; "Yours" vs "<owner>'s" everywhere (web, lobby, Discord)
- [x] Minecraft linking: code shown in-game (kick screen / lobby menu item), entered on the web; linked names act as the user
- [x] Discord shows Zenith's original embeds, forwarded through `/v1/events`; mentions ping the owner. Every notification can be switched off under Settings → Discord. No chat relay.
- [x] Web auth: first-run admin setup, login sessions, role/grant model in place
- [x] REST + WebSocket API: instances CRUD, start/stop/restart, status, live console, run command
- [x] Web UI v1: setup/login, dashboard, add account, add server, add instance, instance page (console, chat, commands, start/stop)
- [x] Dockerfile (multi-stage: GraalVM native Zenith + manager + UI) and `docker-compose.yml`

### Phase 2: Settings + users (done)
No commands needed anywhere: the console only shows in developer mode (user menu, stored per browser).
- [x] Zenith: `ZENITH_DUMP_SCHEMA` writes config defaults + typed schema; control API `GET/POST /v1/config` changes settings live (reflection, validated before applying)
- [x] Manager: settings API per layer (`/api/settings/global`, `/api/servers/{id}/settings`, `/api/instances/{id}/settings`); values, sources, forced paths; changes pushed to running instances; Mojang name lookup for player lists
- [x] Curated settings pages (connection, survival, safety, AFK, combat, chat, joining, extras) at all three layers, with search, "changed" filter and reset to inherited
- [x] Generic editor for every setting, in developer mode
- [x] Instance page tabs: Overview (status + chat), Settings, Console (developer mode)
- [x] Per-instance outbound proxy with a global admin on/off switch
- [x] Users and sharing UI: role presets + custom capabilities; share by MC name or Discord user without a web login
- [x] Editors for structured lists (waypoints, active hours, pearl loader, server switcher)
- [x] The "Who can play" / spectator whitelist sections are gone; grants decide who may join

### Phase 3: Lobby (done)
- [x] Router on the public MC port (`ZENITH_LOBBY_PORT`, default 25565). Status pings and normal logins go to the lobby. Transfers carrying a `zenith:target` cookie are piped to that instance's localhost port.
- [x] Void lobby (MCProtocolLib, online mode) with a chest menu: one player head per instance the player may join, with its status in the tooltip. Left-click plays, right-click spectates; an offline instance is started first if the player may control it. The compass reopens the menu.
- [x] Clicking sends a Transfer with cookies `zenith:target`, `zenith:zenith-transfer-src=lobby` and `zenith:zenith-spectator`. Zenith's `proxyIP` is forced to the public address, so `/lobby` and spectator swaps come back through the router.
- [x] Instances enable client-side ViaVersion by default (`client.viaversion.enabled`) and join each server at the newest version it supports. The manager reads the Zenith defaults before restoring instances, so changed defaults apply after an image update.
- [x] Clients from 1.20.5 (the first version with transfers) up to the newest version ViaVersion knows (26.3 with Via 5.12.1). The lobby embeds ViaVersion and ViaBackwards and translates to its own 1.21.4; instances translate transferred clients with Zenith's own server-side Via. Older and unknown versions get a clear message, and their server list shows the lobby as incompatible. Players without any grant are kicked with "You are not whitelisted on this server."
- [x] Zenith's whitelist and spectator whitelist are forced from grants (name→UUID directory in `/data/manager/players.json`) and pushed live when grants change.
- `ZENITH_LOBBY_ONLINE_MODE=false` exists for local testing only.

### Phase 4: Central Discord bot (done, optional)
- [x] One bot token, set on the Settings page (stored 0600 in `/data/manager/secrets`, never returned by the API); enable switch, status and invite link.
- [x] Category per account, channel per instance, created and cleaned up automatically; member overrides synced from grants and linked users.
- [x] Discord linking with a one-time code (`/link CODE`, 10 minutes) from the user menu; unlink in the same dialog.
- [x] Slash commands in an instance channel (`/status`, `/start`, `/stop`, `/connect`, `/disconnect`, `/command`, `/pearl`), checked against the linked user's capabilities.
- Not yet tested against a live Discord server.

### Phase 5: Controls, pearls and tasks (done, 2026-10-29)
Everything a user did with commands now has a UI, except lookups (seen, stats, …), plugins and map rendering, which aren't supported.
- [x] Zenith control API `GET /v1/details`: inventory or open container (slot numbers as Zenith's inventory command uses them), pathfinder state, replay recording, scheduled tasks. Command results flag error embeds (`embed.error`, the theme's error colour).
- [x] Manager `POST /api/instances/{id}/action`: structured actions (`instances/Actions`) turn into Zenith commands. Every argument is checked against a strict pattern, and each action names the capability it needs: movement, clicking, inventory and looking need PLAY; requeue, kick, transfer, recording and tasks need CONTROL; waypoints need SETTINGS; free text (`command`) needs COMMANDS and only shows in developer mode. A task wraps another action and needs CONTROL plus that action's capability.
- [x] Instance tabs: **Control** (movement modes, waypoints, clicking, looking, respawn, inventory grid with hold/move/drop and container take/put, players with kick and transfer, requeue, recording with download), **Pearls**, **Tasks** (once later, repeating, or on an event; built from the same actions). Details are polled every 2 s while a tab is open.
- [x] Stasis pearls: a `PEARLS` capability ("Load stasis pearls", in every role from Spectator up) loads any of the instance's pearls. The list is the PearlLoader setting; people with SETTINGS add, edit and remove pearls in the Pearls tab. Discord `/pearl <pearl>` (with autocomplete) in the instance channel.
- [x] Pearl webhooks: in developer mode each person gets their own secret URL per pearl (`/hooks/pearl/<token>`, 192 random bits, stored 0600 in `secrets/pearl-webhooks.json`). POST loads the pearl as that person and only while they still have PEARLS; GET shows a button, so link previews load nothing. 5 s cooldown per URL. New URL replaces the old one; deleting the instance or the user drops them.
- [x] Connected players card (Control tab, CONTROL): everyone connected through the proxy, playing or spectating, with one-click Kick, Kick everyone, **Make player** (a spectator takes over; whoever was playing starts spectating) and **Make spectator**. The managed-only Zenith command `controller set <player>` / `controller release` does the swap with the same transfers as the in-game `swap force`, and only accepts the manager as source. Make player is greyed out, with the reason shown, for spectators without play access and clients older than 1.20.5.
- [x] Connection history (collapsible under Connected players, CONTROL): Zenith's control API sends `player` events (joined/left, player or spectator, disconnect reason) over `/v1/events`. `PlayerHistory` keeps the last 200 per instance in `manager/history/<id>.json`, merges a leave and rejoin of the same name within 15 s into one "switched" entry, and names who kicked or swapped from the web UI. Transfer reasons are dropped. `GET /api/instances/{id}/history`; deleting the instance deletes its history.
- [x] Nearby players card (Control tab): other players in the bot's visual range from `/v1/details` (`nearby`: tab-list name, position, distance, friend), nearest first, with distance, compass direction, coordinates and, with PLAY, **Follow** and **Go to**.
- [x] Control tab layout: an activity bar on top (what the bot is doing: moving, holding a click, recording; position; one **Stop** that ends pathing and held clicks; `/v1/details` reports `clicking`), then four views so nothing is a long scroll away on a phone: **Move** (Go to, Fly, Follow and Mine up front, the other modes behind "More"; waypoints beside it), **Players** (connected players with history, nearby players), **Inventory**, and **More** (click and look, with exact angles folded away; transfer and requeue; recording). The view is kept in `?view=`, and views the user has no capability for are hidden.
- [x] Settings cover every Zenith setting. The rarely used ones (plus the Network and Troubleshooting sections) carry an Advanced tag and stay hidden until "Show advanced settings" is switched on (per browser, `localStorage.settingsAdvanced`). Search always finds them.
- [x] Recordings: `/api/instances/{id}/replays` lists, downloads and deletes the `.mcpr` files in the instance's `replays` folder (CONTROL).

### Later
- Browser notifications (queue done, low health, visual range, …).

## Removed in the fork
Deleted from the code, not just switched off (2026-10-29):
- Zenith's own Discord bot (JDA login, presence, nickname/avatar, buttons, Discord command input). `com.zenith.discord.DiscordBot` only posts `MainChannelMessageSentEvent`s, which the control API forwards to the manager's bot. The `discord` and `chatRelay` commands and their config are gone.
- The Discord chat relay (listener, events, config).
- The interactive JLine terminal and the `terminal` command. `TerminalManager` only prints logs to stdout.
- The self-updater, its events and the `update`, `autoUpdate`, `channel` and `jvmArgs` commands. `LaunchConfig` keeps only version information.
- The Python launcher (`src/launcher`), `scripts/` install and run helpers, the launcher/release/prerelease workflows, and the wiki setup guides.
