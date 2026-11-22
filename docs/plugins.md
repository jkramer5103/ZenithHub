# ZenithHub plugins

A plugin is a zip file the admin installs under **Settings → Plugins**. It can add to or change any part of
ZenithHub:

- **Manager:** Java code that runs inside the ZenithHub manager. It can use every service (accounts, instances,
  settings, the lobby's player directory, the Discord bot, flows…), add HTTP routes, replace built-in routes and
  change the manager's own classes with mixins.
- **Bot:** Java code that runs inside every ZenithProxy instance. It can use everything in ZenithProxy (the event
  bus, modules, commands, the cache, the client and server sessions), answer routes and send events to the manager.
  It can also change any ZenithProxy class with mixins.
- **Web:** ES modules that every signed-in browser loads. They can add pages, Settings pages, instance tabs and
  dashboard cards, listen to the live WebSocket, and do anything else a script on the page can do.

There is no sandbox. A plugin can read every account's sign-in, every setting and every secret, and act as any user.
Only the admin can install plugins, and the install dialog says so.

A working example with all three parts and mixins is in [`examples/hello-plugin`](../examples/hello-plugin). Run
`build.sh` there to get `hello-plugin.zip`.

## The zip

```
plugin.json
manager/*.jar      the manager part (optional)
bot/*.jar          the bot part (optional)
web/index.js       the web part (optional), plus any files it loads
web/index.css
```

`plugin.json` can sit at the top of the zip or in its only folder, so zipping a folder works. Limits: 128 MB zipped,
512 MB unpacked, 5000 files.

### plugin.json

```json
{
  "id": "hello",
  "name": "Hello",
  "version": "1.0.0",
  "description": "What it does, shown before installing.",
  "authors": ["you"],
  "url": "https://example.com",
  "manager": { "main": "example.hello.HelloManager", "mixins": ["example.hello.mixins.OverviewMixin"] },
  "bot": { "main": "example.hello.HelloBot", "mixins": ["example.hello.mixins.StatusMixin"] },
  "web": { "scripts": ["web/index.js"], "styles": ["web/index.css"] },
  "settings": [
    { "key": "greeting", "label": "Greeting", "type": "string", "default": "Hello", "public": true },
    { "key": "apiKey", "label": "API key", "type": "secret" }
  ]
}
```

- `id`: lowercase letters, digits, `-` and `_`, up to 40 characters. It must stay the same across versions, because
  an upload with an installed id is an update.
- `manager` / `bot`: `jars` lists jars inside the zip. Without it, every jar under `manager/` or `bot/` is used.
  `main` is a class with a public no-argument constructor. `mixins` lists mixin classes. A part can have only
  mixins and no `main`.
- `web`: `scripts` and `styles` are files under `web/`. Each list defaults to `web/index.js` or `web/index.css` if
  that file exists.
- `settings`: the form on the Plugins page. Types are `boolean`, `number` (`min`, `max`), `string`, `text`,
  `select` (`options: [{value, label}]`) and `secret`. Values are saved in
  `/data/manager/secrets/plugin-settings/<id>.json` (0600). The manager and bot parts get every value. Browsers
  only get settings marked `"public": true`, and never secrets.

## When changes apply

| Part    | Loads                                                                                                    |
|---------|----------------------------------------------------------------------------------------------------------|
| Manager | when ZenithHub starts. The Plugins page shows **Restart now**, and the container's entrypoint starts the manager again (exit code 75). Instances keep running. |
| Bot     | when an instance (re)starts. The Plugins page lists the running instances that use older plugins and can restart them, a few seconds apart. |
| Web     | when the page reloads. Asset URLs carry the plugin's revision, so browsers never use stale files.       |
| Settings | right away: `onSettingsChanged` in the manager and in every running bot. The web part gets public settings on the next reload. |

Instances run the native ZenithProxy image unless some enabled plugin has a bot part. Then they run
`ZENITH_JVM_COMMAND` (`java … -jar /app/zenith/ZenithProxy.jar`), because native images can't load jars. That
uses more memory per instance.

**Safe mode:** if the manager fails to finish starting twice in a row with plugins, the next start skips manager
plugins and the Plugins page explains why. `ZENITH_SAFE_MODE=1` forces it. Turn off or remove the plugin that broke
the start, then restart. Bot plugin errors show up per plugin on the Plugins page.

## Manager part

Compile against the manager jar (`/app/manager.jar` in the image, or `manager/build/libs/zenith-manager.jar`). It
contains Javalin, Gson, SLF4J and the mixin annotations.

```java
public final class HelloManager implements ZenithHubPlugin {
    @Override
    public void onLoad(PluginContext ctx) {
        ctx.routes(app -> app.get("/api/plugins/hello/summary", http -> {
            var user = ctx.requireUser(http);           // 401 unless signed in
            var json = new JsonObject();
            json.addProperty("user", user.username);
            http.json(json.toString());                 // Gson: the manager has no Jackson, so pass a JSON string
        }));
        ctx.onBotEvent((instanceId, event, data) -> ctx.logger().info("{}: {}", instanceId, event));
    }
}
```

`ZenithHubPlugin`: `onLoad(ctx)`, then `onStarted()` once everything runs, `onSettingsChanged(settings)` and
`onStop()`.

`PluginContext`:

- `id()`, `manifest()`, `directory()` (the unpacked zip, replaced on updates), `dataDir()`
  (`/data/plugin-data/<id>`, kept across updates), `settings()`, `logger()`.
- `hub()`: every service. That's `config`, `store` (state.json: users, instances, servers, grants), `events`,
  `accounts`, `supervisor`, `settings`, `proxies`, `proxyPool`, `proxyOverrides`, `access`, `players`, `pearls`,
  `history`, `afkHistory`, `discord`, `flows`, `web` and `plugins`.
- `javalin(cfg -> …)`: changes the web server's configuration before it's created. Only works in `onLoad`.
- `routes(app -> …)`: adds routes and handlers ahead of the built-in ones. Only works in `onLoad`. A `before`
  handler that calls `ctx.skipRemainingHandlers()` replaces a built-in route. Put the plugin's own API under
  `/api/plugins/<id>/…`. Mutating `/api` requests must be JSON (CSRF protection), and integration tokens can't
  reach plugin routes.
- `user(ctx)`, `requireUser(ctx)`, `requireAdmin(ctx)`, `requireCapability(ctx, instanceId, Capability.X)`: the
  same permission checks the built-in routes use.
- `onBotEvent(listener)`: events from the plugin's bot part.
- `publish(event, instanceId, data)`: a `plugin` WebSocket message. With an instance it goes to everyone who can
  see that instance. With `null` it goes to everyone signed in.
- `callBot(instanceId, method, path, body)`: calls the bot part's routes in a running instance.

Outbound requests must use `hub().proxies()` (`OutboundProxies`), so they respect the admin's outbound proxy.

## Bot part

Compile against `ZenithProxy.jar` (`/app/zenith/ZenithProxy.jar`, or `build/libs/ZenithProxy.jar`).

```java
public final class HelloBot implements ZenithHubBotPlugin {
    @Override
    public void onLoad(BotPluginContext ctx) {
        EVENT_BUS.subscribe(this, of(ClientOnlineEvent.class, e -> ctx.emit("online", new JsonObject())));
        ctx.route("stats", request -> {
            var json = new JsonObject();
            json.addProperty("tps", TPS.getTPS());
            return json;
        });
    }
}
```

`ZenithHubBotPlugin`: `onLoad(ctx)` after ZenithProxy has started, then `onSettingsChanged(settings)` and `onStop()`.

`BotPluginContext`: `id()`, `version()`, `instanceId()`, `settings()`, `dataDir()`
(`<instance>/plugin-data/<id>`), `logger()`, `emit(event, data)` (to the manager part and the web UI) and
`route(path, handler)`. A route answers `/v1/plugin/<id>/<path>` on the instance's control API. The web UI reaches
it through `/api/plugins/<id>/instances/<instance>/bot/<path>`: GET needs View, other methods need Control.

Use `com.zenith.control.OutboundProxy` for outbound connections. Users never type commands: if a plugin registers
Zenith commands, give them a UI too.

## Mixins

Both parts use [ClassTransform](https://github.com/Lenni0451/ClassTransform) with its Mixin translator, so the
familiar `org.spongepowered.asm.mixin` annotations work: `@Mixin`, `@Inject`, `@Redirect`, `@ModifyConstant`,
`@Overwrite`, `@Shadow` and so on. Name the target with `@Mixin(targets = "…")` or a class literal. Mixins apply as
classes load. A mixin that fails to apply is listed on the Plugins page, and the class loads without it.

```java
@Mixin(targets = "com.zenith.manager.web.WebServer")
public abstract class OverviewMixin {
    @Inject(method = "overview", at = @At("RETURN"))
    private void hello$overview(State.User user, CallbackInfoReturnable<JsonObject> cir) {
        cir.getReturnValue().addProperty("helloMixin", "patched");
    }
}
```

Plugin classes load child-first, so a plugin can bundle its own libraries in its jars. The manager shares Gson
with plugins, so pass Gson types freely.

## Web part

`web/index.js` is an ES module. Its default export (or a `setup` export) gets the hub:

```js
export default function setup(hub) {
	hub.addPage({ path: '', title: 'Hello', nav: true, icon: '<svg …>', mount(el, { rest, url }) {
		el.textContent = `${hub.plugin.settings.greeting}, ${hub.app.user.username}`;
		return () => {}; // cleanup, optional
	} });
}
```

- `addPage({path, title, icon?, nav?, admin?, mount})`: a page at `/ext/<id>/<path>`. With `nav` it goes in the
  top bar and the phone's bottom bar. `icon` is SVG markup (24×24, `stroke="currentColor"`).
- `addSettingsPage({path, title, description?, icon?, admin?, mount})`: a page under Settings at
  `/settings/ext/<id>/<path>`.
- `addInstanceTab({id, label, icon?, show?(instance), mount(el, {instanceId, instance})})`: a tab on the instance
  page. Use `show` to check `instance.capabilities`.
- `addDashboardCard({id, mount})`: a card at the bottom of the dashboard.
- `api.get/post/put/patch/del(path, body)`: the ZenithHub API (`/api` + path) as the signed-in user, and
  `api.bot(instanceId, method, path, body)` for the plugin's bot routes.
- `on(type | '*', listener)`: WebSocket messages. `onEvent(event | '*', (data, instanceId, event) => …)`: the
  plugin's own events from `emit` and `publish`.
- `app`: the reactive app state (`app.user`, `app.overview.instances`, `app.instance(id)`, `app.isAdmin`…).
- `toast.success/error`, `goto(url)`, `asset(path)` (a URL for a file in `web/`), and `plugin` (`id`, `name`,
  `version`, public `settings`).

`mount(el, ctx)` gets an empty element and can return a cleanup function. It may be async. The page's Tailwind
classes (`card`, `btn-primary`, `btn-secondary`, `input`, the zinc palette…) are available. Plugin assets are only
served to signed-in users.

## Files on disk

| Path | What |
|------|------|
| `/data/manager/plugins.json` | installed plugins: id, on/off, revision |
| `/data/plugins/<id>/<revision>/` | the unpacked zip. Each install gets a new revision, and old ones are deleted once nothing runs them |
| `/data/plugin-data/<id>/` | the manager part's data |
| `/data/instances/<instance>/plugin-data/<id>/` | the bot part's data |
| `/data/manager/secrets/plugin-settings/<id>.json` | settings (0600) |

Removing a plugin keeps its data and settings unless the admin ticks **Also delete its settings and data**.
