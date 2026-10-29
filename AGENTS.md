# AGENTS.md

A hard fork of rfresh2/ZenithProxy: a Docker-only, multi-account manager with a web UI. Read
[docs/revamp/PLAN.md](docs/revamp/PLAN.md) first. It is the source of truth for the design and
records what is done. Keep it up to date when you change behaviour.

## Layout

- `src/`: ZenithProxy (the bot). Upstream code, minus the parts listed under "Removed in the fork"
  in PLAN.md (its own Discord bot, chat relay, terminal input, self-updater, launcher). Don't bring
  those back. Otherwise keep fork changes small so upstream merges stay easy. Fork-specific code
  lives in `com.zenith.control` (managed mode and control API) and `command/impl/LobbyCommand.java`.
  `discord/DiscordBot.java` no longer logs in to Discord: it posts events that the control API
  forwards to the manager's central bot.
- `manager/`: the manager (Java 25, Javalin, MCProtocolLib, ViaVersion). Packages: `accounts`
  (MSA login, token refresh), `auth` (users, grants, capabilities), `instances` (supervisor,
  settings layering, config composer), `lobby` (router, lobby server, player
  directory), `discord` (central bot), `web` (REST/WebSocket API), `state` (state.json).
- `web/`: SvelteKit static UI with Tailwind and Svelte 5 runes. The curated settings pages are
  defined in `src/lib/settings-catalog.ts`, and search lives in `src/lib/settings-search.ts`.
- `Dockerfile`, `docker-compose.yml`: the only supported way to deploy.

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
- Copy buttons must work over plain `http://<ip>`, where `navigator.clipboard` is missing.

## Gotchas

- Whitelists need Mojang (online) UUIDs. Offline-mode logins produce version-3 UUIDs; never store
  those in the player directory.
- The lobby strips the enchantment registry: MCProtocolLib sends no tags, and 26.x clients reject
  enchantments that reference unbound tags.
- Avoid `backdrop-blur` on sticky elements: headless Chromium without a GPU stops rendering tall pages.
- Frontend changes need screenshots, desktop and mobile, before they count as done.

## Git

- `origin` is this fork (jkramer5103/ZenithProxy) and `upstream` is rfresh2/ZenithProxy. Merge
  upstream into `1.21.4` to pick up bot fixes.
- Commit only when asked.
