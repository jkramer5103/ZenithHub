# Integrations API

Other programs (like the Blockbot Minecraft add-on) use ZenithHub with an **integration token** from
**Settings → Integrations**. A token acts as the person who made it, limited to the bots and permissions
they ticked, and never more than that person may do right now.

Send it as `Authorization: Bearer zh_…`. Requests with a body must be `Content-Type: application/json`.

## What a token reaches

| Request | Needs | What it does |
| --- | --- | --- |
| `GET /api/integration` | | `{user, token, instances:[{id, name, accountName, mine, ownerName, capabilities, runtime}]}` |
| `GET /api/instances/{id}/details` | VIEW | Position, health, inventory, nearby players, pathfinder, tasks, waypoints |
| `GET /api/instances/{id}/pearls` | PEARLS | `[{id, x, y, z}]` |
| `GET /api/instances/{id}/logs?limit=` | VIEW | Recent console lines |
| `GET /api/instances/{id}/afk` | VIEW | AFK zone earnings |
| `POST /api/instances/{id}/action` | depends | Structured actions, see below |
| `POST /api/instances/{id}/chat` | CHAT | `{message}` |
| `POST /api/instances/{id}/command` | COMMANDS | `{command}`, any ZenithProxy command |
| `POST /api/instances/{id}/connect` / `disconnect` | CONTROL | |

Anything else answers 403. An unknown or deleted token answers 401; an instance the token doesn't cover answers 404.

### Actions

`POST /api/instances/{id}/action` with `{action, …}`. The same actions the web UI uses (`manager/.../instances/Actions.java`), for example:

- `{action:"loadPearl", pearl}` (PEARLS)
- `{action:"goto", x, y?, z}`, `gotoWaypoint`, `follow {player?}`, `mine {block}`, `stop` (PLAY)
- `elytra {x, y?, z}`, `thisway {blocks}`, `getTo {block}`, `pickup {item?}`, `near {x, y, z, range}`, `clickBlock {button, x, y, z | waypoint}`, `clickEntity {button, entity}`, `breakBlock {x, y, z}`, `placeBlock {x, y, z, item}`, `clearArea {from, to}`, `rotate {yaw, pitch}` (PLAY)
- `{action:"module", module, on}`: switches a module (autoEat, antiAFK, killAura, …) on or off (SETTINGS)
- `{action:"hold", slot}`, `drop`, `swap`, `withdraw`, `deposit` (PLAY)
- `requeue`, `kick {player?}`, `makePlayer {player}`, `makeSpectator`, `afkEnter`, `afkLeave` (CONTROL / PLAY)
- `{action:"notify", text}`: a "Notify me" alert to the owner (CONTROL)
- `{action:"command", command}` (COMMANDS)

## Live events

Open `ws(s)://<hub>/api/ws` with the same header. Every message is `{type, instanceId, data}`. You get:

- `runtime`: `{phase, lastError, status}` whenever it changes. `phase` is the process (`STOPPED`, `STARTING`, `RUNNING`, `BACKOFF`…); `status` is the bot's own: `online`, `connected`, `inQueue`, `queuePosition`, `queueEtaSeconds`, `health`, `food`, `x`, `y`, `z`, `dimension`, `controller`, `server`, `tps`, `ping`, `lastDisconnectReason`. While in the game it also has `alive`, `position.yaw`/`pitch`, `pathing {active, process?}`, `entities` (the nearest 40 within 64 blocks: `{id, type, kind: player|hostile|mob|item|vehicle|other, name?, friend?, x, y, z, distance}`), `items` (`{itemId: count}` of the bot's own inventory), `freeSlots`, `held`, `durability` (`[{slot, item, left, max}]` of the hands and armour), `container {title, size}` while one is open, and `raining`.
- `alert`: "Notify me" alerts from tasks.
- `game`: what happens to the bot in game. `data.event` is one of
  - `totem` `{totemsLeft, health, x, y, z}`: the bot popped a totem
  - `noTotems`
  - `attacked` `{attacker, attackerUuid, health, x, y, z}`
  - `health` `{health, previous}`: when the shown hearts change
  - `death` `{x, y, z}`
  - `visualRange` `{action: enter|leave|logout, player, playerUuid, x, y, z, friend}`
  - `whisper` `{from, uuid, message}`: whispers the bot receives
  - `chat` `{from?, uuid?, message}`
  - `queueComplete` `{seconds}`, `serverRestarting` `{message}`
  - `serverJoin`, `serverLeave` `{player, playerUuid, friend}`: someone joins or leaves the server (not in the queue, nor in the first 10 s after joining)
  - `outOfFood`: auto eat has nothing left to eat
- `changed`: something in ZenithHub changed; refetch `/api/integration` if you show instance lists.

Send `{"subscribe":[instanceId,…]}` to also get `log` lines of those instances.

Deleting the token closes the socket with code 4001.
