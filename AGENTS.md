# AGENTS.md

ZenithHub, a hard fork of rfresh2/ZenithProxy: a Docker-only, multi-account manager with a web UI. Read
[docs/revamp/PLAN.md](docs/revamp/PLAN.md) first. It is the source of truth for the design and
records what is done. Keep it up to date when you change behaviour.

## Layout

- `src/`: ZenithProxy (the bot). Upstream code, minus the parts listed under "Removed in the fork"
  in PLAN.md (its own Discord bot, chat relay, terminal input, self-updater, launcher). Don't bring
  those back. Otherwise keep fork changes small so upstream merges stay easy. Fork-specific code
  lives in `com.zenith.control` (managed mode, control API, outbound proxy), `command/impl/LobbyCommand.java` and
  `command/impl/ControllerCommand.java`.
  `discord/DiscordBot.java` no longer logs in to Discord: it posts events that the control API
  forwards to the manager's central bot.
- `manager/`: the manager (Java 25, Javalin, MCProtocolLib, ViaVersion). Packages: `accounts`
  (MSA login, token refresh), `auth` (users, grants, capabilities), `instances` (supervisor,
  settings layering, config composer), `lobby` (router, lobby server, player
  directory), `discord` (central bot), `web` (REST/WebSocket API), `state` (state.json).
- `web/`: SvelteKit static UI with Tailwind and Svelte 5 runes. The curated settings pages are
  defined in `src/lib/settings-catalog.ts`, and search lives in `src/lib/settings-search.ts`.
- `Dockerfile`, `docker-compose.yml` (builds from the checkout), `quickstart/docker-compose.yml`
  (pulls the published image): the only supported ways to deploy. `.github/workflows/ci.yml`
  runs the checks below on every push and PR (docs-only changes skip it). The image builds in
  parallel and is tagged `latest` on `main` only once the checks pass.

## Build and check

```sh
./gradlew -q :shadowJar :manager:shadowJar
./gradlew :manager:test
cd web && npm run build && npm run check
```

Run prettier only on `.ts` files. No Svelte plugin is installed, so it fails on `.svelte` files.

## Product rules

- Users never type commands. Every feature needs a clean UI. The console and the generic
  every-setting editor exist only behind the per-browser "Developer mode" toggle.
- Users see one Connect and one Disconnect, never start/stop. Status words: Offline, Connecting,
  Online, Queue, Crashed.
- Always make clear what is "yours" and what belongs to a friend (web, lobby lore, Discord).
- One permission system (grants and capabilities) covers the web, the lobby and Discord. The
  admin is a superuser.
- Discord is optional. It posts Zenith's original embeds, and every notification must be
  switchable under Settings → Discord. The chat relay was removed on purpose: don't add it back.
- Settings layer Zenith defaults → Global → Server → Instance → forced managed values
  (`ConfigComposer`). Instance changes made in-game are diffed back into overrides on stop.
- Secrets are files with 0600 permissions under `/data/manager/secrets`. The API never returns them.
- `ZENITH_DISABLE_CHEATS` lists cheat modules twice: `com.zenith.control.Cheats` (bot) and
  `manager/.../instances/Cheats.java` (settings). Change both together.
- Copy buttons must work over plain `http://<ip>`, where `navigator.clipboard` is missing.
- While an outbound proxy is on, everything an instance or the manager sends for it goes through it (see PLAN.md). New outbound requests must use `OutboundProxy` (bot) or `OutboundProxies` (manager), never a bare `HttpClient` or `Socket`.

## Gotchas

- Whitelists need Mojang (online) UUIDs. Offline-mode logins produce version-3 UUIDs; never store
  those in the player directory.
- The lobby strips the enchantment registry: MCProtocolLib sends no tags, and 26.x clients reject
  enchantments that reference unbound tags.
- Avoid `backdrop-blur` on sticky elements: headless Chromium without a GPU stops rendering tall pages.
- Frontend changes need screenshots, desktop and mobile, before they count as done.

## Naming

- The product is ZenithHub everywhere users see it (web UI, lobby, Discord, docs, image). The bot
  it runs is still ZenithProxy, and code keeps the `com.zenith` packages, `ZENITH_*` env vars and
  the `ZenithProxy` binary name so upstream merges stay easy.

## Git

- One branch: `main`. Zenith's native version is 26.3 (upstream's `26.3.0` branch) and ViaVersion
  covers every other version in both directions, so there are no per-version branches like upstream's.
- `origin` is this repo (jkramer5103/ZenithHub) and `upstream` is rfresh2/ZenithProxy. Merge
  `upstream/26.3.0` into `main` to pick up bot fixes. When a newer Minecraft release comes out, merge
  upstream's branch for it and recapture `src/main/resources/mcdata/core-registries.bin.gz` and
  `en_us.json.gz` from that vanilla version (see PLAN.md).
- Commit only when asked.
