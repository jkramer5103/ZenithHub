package com.zenith.manager.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.accounts.AccountService;
import com.zenith.manager.auth.AccessService;
import com.zenith.manager.discord.DiscordBot;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Role;
import com.zenith.manager.auth.LoginThrottle;
import com.zenith.manager.auth.Passwords;
import com.zenith.manager.auth.Permissions;
import com.zenith.manager.auth.Sessions;
import com.zenith.manager.instances.Actions;
import com.zenith.manager.instances.PearlService;
import com.zenith.manager.instances.PlayerHistory;
import com.zenith.manager.instances.SettingsService;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * REST + WebSocket API under /api, and the static web UI everywhere else.
 */
public final class WebServer {
    private static final Logger LOG = LoggerFactory.getLogger("Web");
    private static final String SESSION_COOKIE = "zsid";
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");

    private final ManagerConfig config;
    private final StateStore store;
    private final Sessions sessions;
    private final LoginThrottle throttle = new LoginThrottle();
    private final AccountService accounts;
    private final Supervisor supervisor;
    private final EventHub events;
    private final SettingsService settings;
    private final AccessService access;
    private final DiscordBot discord;
    private final PearlService pearls;
    private final PlayerHistory history;
    private final Map<WsContext, WsClient> wsClients = new ConcurrentHashMap<>();
    private Javalin app;

    public WebServer(ManagerConfig config, StateStore store, AccountService accounts, Supervisor supervisor, EventHub events, SettingsService settings, AccessService access, DiscordBot discord, PearlService pearls, PlayerHistory history) {
        this.config = config;
        this.pearls = pearls;
        this.history = history;
        this.discord = discord;
        this.access = access;
        this.settings = settings;
        this.store = store;
        this.accounts = accounts;
        this.supervisor = supervisor;
        this.events = events;
        this.sessions = new Sessions(config.dataDir().resolve("manager").resolve("sessions.json"));
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 1_000_000L;
            if (Files.isDirectory(config.webDir())) {
                cfg.staticFiles.add(sf -> {
                    sf.directory = config.webDir().toString();
                    sf.location = Location.EXTERNAL;
                });
            } else {
                LOG.warn("Web UI directory {} not found, serving the API only", config.webDir());
            }
        });

        app.exception(ApiException.class, (e, ctx) -> error(ctx, e.status, e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(IllegalStateException.class, (e, ctx) -> error(ctx, 409, e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("API error on {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "Internal error: " + e.getMessage());
        });

        // mutations must be JSON: blocks cross-site form posts on top of the SameSite cookie
        app.before("/api/*", ctx -> {
            var method = ctx.method().name();
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS")) {
                var type = ctx.contentType();
                if (type == null || !type.startsWith("application/json")) throw new ApiException(415, "Expected application/json");
            }
        });

        registerSessionRoutes();
        registerAccountRoutes();
        registerServerRoutes();
        registerInstanceRoutes();
        registerSettingsRoutes();
        registerAccessRoutes();
        registerControlRoutes();
        registerWebSocket();
        // single page app: unknown non-API paths get index.html, the client-side router takes over
        var index = config.webDir().resolve("index.html");
        app.error(404, ctx -> {
            if (ctx.path().startsWith("/api") || ctx.method() != HandlerType.GET || !Files.exists(index)) return;
            ctx.status(200).contentType("text/html").result(Files.readString(index));
        });
        // unknown API paths are errors
        for (var method : List.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE)) {
            app.addHttpHandler(method, "/api/*", ctx -> {
                throw ApiException.notFound();
            });
        }

        events.subscribe(this::onEvent);
        app.start(config.webBind(), config.webPort());
        LOG.info("Web UI listening on http://{}:{}", config.webBind(), config.webPort());
    }

    public void stop() {
        if (app != null) app.stop();
    }

    // --- sessions & users ---

    private void registerSessionRoutes() {
        app.get("/api/session", ctx -> {
            var json = new JsonObject();
            boolean setupRequired = store.read(s -> s.users.isEmpty());
            json.addProperty("setupRequired", setupRequired);
            var user = currentUser(ctx);
            json.add("user", user == null ? com.google.gson.JsonNull.INSTANCE : userView(user));
            send(ctx, json);
        });

        app.post("/api/setup", ctx -> {
            var body = body(ctx);
            var username = requireString(body, "username");
            var password = requireString(body, "password");
            validateCredentials(username, password);
            var userId = store.updateAndGet(s -> {
                if (!s.users.isEmpty()) throw new ApiException(409, "Setup is already done");
                var user = new State.User();
                user.id = UUID.randomUUID().toString();
                user.username = username;
                user.passwordHash = Passwords.hash(password);
                user.admin = true;
                user.createdAt = System.currentTimeMillis();
                s.users.add(user);
                return user.id;
            });
            LOG.info("Created admin user {}", username);
            startSession(ctx, userId);
            send(ctx, ok());
        });

        app.post("/api/login", ctx -> {
            var body = body(ctx);
            var username = requireString(body, "username");
            var password = requireString(body, "password");
            var ipKey = "ip:" + ctx.ip();
            var userKey = "user:" + username.toLowerCase(Locale.ROOT);
            long wait = Math.max(throttle.retryAfterMs(ipKey), throttle.retryAfterMs(userKey));
            if (wait > 0) throw new ApiException(429, "Too many attempts, try again in " + (wait / 1000 + 1) + "s");
            var user = store.read(s -> s.users.stream().filter(u -> u.username.equalsIgnoreCase(username)).findFirst().orElse(null));
            if (user == null || !Passwords.verify(password, user.passwordHash)) {
                throttle.failure(ipKey);
                throttle.failure(userKey);
                throw new ApiException(401, "Wrong username or password");
            }
            throttle.success(ipKey);
            throttle.success(userKey);
            startSession(ctx, user.id);
            send(ctx, ok());
        });

        app.post("/api/logout", ctx -> {
            sessions.invalidate(ctx.cookie(SESSION_COOKIE));
            ctx.removeCookie(SESSION_COOKIE, "/");
            send(ctx, ok());
        });

        app.get("/api/overview", ctx -> send(ctx, overview(requireUser(ctx))));
    }

    private void startSession(Context ctx, String userId) {
        var cookie = new Cookie(SESSION_COOKIE, sessions.create(userId));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSameSite(SameSite.STRICT);
        cookie.setMaxAge(30 * 24 * 3600);
        cookie.setSecure("https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")) || ctx.scheme().equals("https"));
        ctx.cookie(cookie);
    }

    private static void validateCredentials(String username, String password) {
        AccessService.validateCredentials(username, password);
    }

    // --- accounts: everyone manages their own, admins all ---

    private void registerAccountRoutes() {
        app.post("/api/accounts/login", ctx -> {
            var user = requireUser(ctx);
            var relogin = optString(body(ctx), "accountId");
            if (relogin != null) requireAccount(user, relogin);
            send(ctx, accounts.startDeviceCodeLogin(relogin, user.id).toJson());
        });
        app.get("/api/accounts/login/{id}", ctx -> send(ctx, requireLogin(ctx).toJson()));
        app.delete("/api/accounts/login/{id}", ctx -> {
            accounts.cancelLogin(requireLogin(ctx).id);
            send(ctx, ok());
        });
        app.post("/api/accounts/import", ctx -> {
            var user = requireUser(ctx);
            String accountId;
            try {
                accountId = accounts.importAuthCache(requireString(body(ctx), "authCache"), user.id);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.badRequest("Couldn't use that auth cache: " + e.getMessage());
            }
            changed();
            send(ctx, idJson(accountId));
        });
        app.post("/api/accounts/offline", ctx -> {
            var user = requireUser(ctx);
            var id = accounts.addOffline(requireString(body(ctx), "username"), user.id);
            changed();
            send(ctx, idJson(id));
        });
        app.post("/api/accounts/{id}/refresh", ctx -> {
            requireAccount(requireUser(ctx), ctx.pathParam("id"));
            try {
                accounts.refresh(ctx.pathParam("id"), true);
            } catch (Exception e) {
                throw new ApiException(502, "Refresh failed, log in again: " + e.getMessage());
            } finally {
                changed();
            }
            send(ctx, ok());
        });
        app.delete("/api/accounts/{id}", ctx -> {
            requireAccount(requireUser(ctx), ctx.pathParam("id"));
            accounts.delete(ctx.pathParam("id"));
            changed();
            send(ctx, ok());
        });
    }

    /** The account, if the user owns it or is an admin; others don't learn it exists. */
    private State.Account requireAccount(State.User user, String accountId) {
        return store.read(s -> s.accounts.stream()
            .filter(a -> a.id.equals(accountId) && (user.admin || user.id.equals(a.ownerId)))
            .findFirst().orElseThrow(ApiException::notFound));
    }

    private AccountService.PendingLogin requireLogin(Context ctx) {
        var user = requireUser(ctx);
        return accounts.pendingLogin(ctx.pathParam("id"))
            .filter(l -> user.admin || user.id.equals(l.ownerId))
            .orElseThrow(ApiException::notFound);
    }

    // --- servers: shared ones from the admin, private ones from anyone ---

    private void registerServerRoutes() {
        app.post("/api/servers", ctx -> {
            var user = requireUser(ctx);
            var body = body(ctx);
            var server = new State.ServerProfile();
            server.id = shortId();
            // the admin's servers are offered to everyone
            server.ownerId = user.admin ? null : user.id;
            applyServerFields(server, body, true);
            store.update(s -> s.servers.add(server));
            changed();
            send(ctx, idJson(server.id));
        });
        app.patch("/api/servers/{id}", ctx -> {
            var id = requireServerOwner(ctx).id;
            var body = body(ctx);
            store.update(s -> applyServerFields(findServer(s, id), body, false));
            changed();
            send(ctx, ok());
        });
        app.delete("/api/servers/{id}", ctx -> {
            var id = requireServerOwner(ctx).id;
            store.update(s -> {
                if (s.instances.stream().anyMatch(i -> i.serverId.equals(id))) throw new IllegalStateException("Delete this server's instances first");
                s.servers.removeIf(x -> x.id.equals(id));
            });
            changed();
            send(ctx, ok());
        });
    }

    /** Servers a user may create instances on. */
    private static boolean canUseServer(State.User user, State.ServerProfile server) {
        return user.admin || server.ownerId == null || server.ownerId.equals(user.id);
    }

    /** Editing a server changes every instance on it: shared servers are the admin's. */
    private State.ServerProfile requireServerOwner(Context ctx) {
        var user = requireUser(ctx);
        var server = store.read(s -> s.servers.stream().filter(x -> x.id.equals(ctx.pathParam("id"))).findFirst().orElseThrow(ApiException::notFound));
        if (user.admin || user.id.equals(server.ownerId)) return server;
        throw server.ownerId == null ? ApiException.forbidden() : ApiException.notFound();
    }

    private static void applyServerFields(State.ServerProfile server, JsonObject body, boolean create) {
        if (create || body.has("name")) server.name = requireString(body, "name").trim();
        if (create || body.has("address")) {
            var address = requireString(body, "address").trim();
            if (!HOST.matcher(address).matches()) throw ApiException.badRequest("Invalid server address");
            server.address = address;
        }
        if (body.has("port")) {
            int port = body.get("port").getAsInt();
            if (port < 1 || port > 65535) throw ApiException.badRequest("Invalid port");
            server.port = port;
        }
        if (server.name.isEmpty()) throw ApiException.badRequest("Name is required");
    }

    // --- instances ---

    private void registerInstanceRoutes() {
        app.post("/api/instances", ctx -> {
            var user = requireUser(ctx);
            var body = body(ctx);
            var name = requireString(body, "name").trim();
            var accountId = requireString(body, "accountId");
            var serverId = requireString(body, "serverId");
            if (name.isEmpty()) throw ApiException.badRequest("Name is required");
            var instance = store.updateAndGet(s -> {
                var account = s.accounts.stream()
                    .filter(a -> a.id.equals(accountId) && (user.admin || user.id.equals(a.ownerId)))
                    .findFirst().orElseThrow(() -> ApiException.badRequest("Unknown account"));
                if (!canUseServer(user, findServer(s, serverId))) throw ApiException.badRequest("Unknown server");
                if (s.instances.stream().anyMatch(i -> i.accountId.equals(accountId) && i.serverId.equals(serverId))) {
                    throw new IllegalStateException("This account already has an instance on that server");
                }
                var i = new State.Instance();
                i.id = shortId();
                i.name = name;
                i.accountId = accountId;
                i.serverId = serverId;
                i.ownerId = account.ownerId;
                i.createdAt = System.currentTimeMillis();
                i.mcPort = freePort(s, config.instancePortBase(), true);
                i.controlPort = freePort(s, config.controlPortBase(), false);
                s.instances.add(i);
                return i;
            });
            changed();
            if (body.has("start") && body.get("start").getAsBoolean()) {
                var actor = actor(ctx);
                Thread.ofVirtual().start(() -> connectQuietly(instance.id, actor));
            }
            send(ctx, idJson(instance.id));
        });
        app.patch("/api/instances/{id}", ctx -> {
            var id = requireOwner(ctx).id;
            var body = body(ctx);
            store.update(s -> {
                var i = findInstance(s, id);
                if (body.has("name")) {
                    var name = body.get("name").getAsString().trim();
                    if (name.isEmpty()) throw ApiException.badRequest("Name is required");
                    i.name = name;
                }
                if (body.has("autostart")) i.autostart = State.Autostart.valueOf(body.get("autostart").getAsString());
            });
            changed();
            send(ctx, ok());
        });
        app.delete("/api/instances/{id}", ctx -> {
            var id = requireOwner(ctx).id;
            supervisor.forget(id);
            pearls.forget(id, null);
            history.forget(id);
            store.update(s -> s.instances.removeIf(i -> i.id.equals(id)));
            SecureFiles.deleteRecursively(config.instanceDir(id));
            changed();
            send(ctx, ok());
        });

        app.post("/api/instances/{id}/start", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            Thread.ofVirtual().start(() -> supervisor.start(id));
            send(ctx, ok());
        });
        app.post("/api/instances/{id}/stop", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            Thread.ofVirtual().start(() -> supervisor.stop(id));
            send(ctx, ok());
        });
        app.post("/api/instances/{id}/restart", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            Thread.ofVirtual().start(() -> supervisor.restart(id));
            send(ctx, ok());
        });
        // starting can take a while; the result shows up in the instance's live status
        app.post("/api/instances/{id}/connect", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            var actor = actor(ctx);
            var result = new JsonObject();
            result.addProperty("since", System.currentTimeMillis());
            Thread.ofVirtual().start(() -> connectQuietly(id, actor));
            send(ctx, result);
        });
        app.post("/api/instances/{id}/disconnect", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            supervisor.disconnect(id, actor(ctx));
            send(ctx, ok());
        });
        app.post("/api/instances/{id}/command", ctx -> {
            var id = requireCapability(ctx, Capability.COMMANDS).id;
            var command = requireString(body(ctx), "command").trim();
            if (command.isEmpty()) throw ApiException.badRequest("Empty command");
            send(ctx, supervisor.command(id, command, actor(ctx)));
        });
        app.post("/api/instances/{id}/chat", ctx -> {
            var id = requireCapability(ctx, Capability.CHAT).id;
            var message = requireString(body(ctx), "message").replace('\n', ' ').trim();
            if (message.isEmpty() || message.length() > 256) throw ApiException.badRequest("Message must be 1-256 characters");
            send(ctx, supervisor.command(id, "say " + message, actor(ctx)));
        });
        app.get("/api/instances/{id}/logs", ctx -> {
            var id = requireCapability(ctx, Capability.VIEW).id;
            int limit = Math.clamp(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(500), 1, 2000);
            var lines = new JsonArray();
            supervisor.runtime(id).logs.tail(limit).forEach(l -> lines.add(Supervisor.logLineJson(l)));
            send(ctx, lines);
        });
    }

    // --- controls: actions, pearls, tasks, replays ---

    private void registerControlRoutes() {
        app.get("/api/instances/{id}/details", ctx -> {
            var id = requireCapability(ctx, Capability.VIEW).id;
            JsonObject details;
            if (supervisor.isRunning(id)) {
                details = supervisor.details(id);
                // the manager's names for task triggers
                if (details.has("tasks")) {
                    for (var t : details.getAsJsonArray("tasks")) {
                        var task = t.getAsJsonObject();
                        if (task.has("event")) task.addProperty("event", Actions.TASK_EVENT_CLASSES.getOrDefault(task.get("event").getAsString(), task.get("event").getAsString()));
                    }
                }
            } else {
                details = new JsonObject();
                details.addProperty("online", false);
                details.add("tasks", new JsonArray());
            }
            details.addProperty("running", supervisor.isRunning(id));
            var waypoints = settings.effective(id, "client.extra.waypoints.waypoints");
            details.add("waypoints", waypoints != null && waypoints.isJsonArray() ? waypoints : new JsonArray());
            send(ctx, details);
        });
        app.post("/api/instances/{id}/action", ctx -> {
            var body = body(ctx);
            var action = Actions.parse(body);
            var instance = requireCapability(ctx, action.capability());
            if (action.alsoNeeds() != null) requireCapability(ctx, action.alsoNeeds());
            if (action.command().startsWith("pearlLoader load ")) {
                var result = pearls.load(instance.id, action.command().substring("pearlLoader load ".length()), actor(ctx));
                if (!result.ok()) throw ApiException.badRequest(result.message());
                var json = new JsonObject();
                json.addProperty("message", result.message());
                send(ctx, json);
                return;
            }
            if (!supervisor.isRunning(instance.id)) throw ApiException.badRequest("Connect the instance first");
            var who = requireUser(ctx).username;
            switch (body.get("action").getAsString()) {
                case "kick" -> history.noteAction(instance.id, "kick", optString(body, "player"), who);
                // the swap reconnects players; whoever ends up switching was swapped by this person
                case "makePlayer", "makeSpectator" -> history.noteAction(instance.id, "swap", null, who);
                default -> {}
            }
            send(ctx, supervisor.command(instance.id, action.command(), actor(ctx)));
        });
        app.get("/api/instances/{id}/history", ctx -> {
            var instance = requireCapability(ctx, Capability.CONTROL);
            send(ctx, history.list(instance.id));
        });

        app.get("/api/instances/{id}/pearls", ctx -> {
            var instance = requireCapability(ctx, Capability.PEARLS);
            var user = requireUser(ctx);
            var list = new JsonArray();
            for (var pearl : pearls.pearls(instance.id)) {
                var json = new JsonObject();
                json.addProperty("id", pearl.id());
                json.addProperty("x", pearl.x());
                json.addProperty("y", pearl.y());
                json.addProperty("z", pearl.z());
                json.addProperty("hasWebhook", pearls.hasWebhook(user.id, instance.id, pearl.id()));
                list.add(json);
            }
            send(ctx, list);
        });
        // the caller's own secret URL for one pearl; created on first use
        app.post("/api/instances/{id}/pearls/{pearl}/webhook", ctx -> {
            var instance = requireCapability(ctx, Capability.PEARLS);
            var user = requireUser(ctx);
            var pearl = ctx.pathParam("pearl");
            if (pearls.pearls(instance.id).stream().noneMatch(p -> p.id().equals(pearl))) throw ApiException.notFound();
            var webhook = pearls.webhook(user.id, instance.id, pearl, optBoolean(body(ctx), "replace"));
            var json = new JsonObject();
            json.addProperty("path", "/hooks/pearl/" + webhook.token());
            send(ctx, json);
        });
        app.delete("/api/instances/{id}/pearls/{pearl}/webhook", ctx -> {
            var instance = requireCapability(ctx, Capability.PEARLS);
            pearls.deleteWebhook(requireUser(ctx).id, instance.id, ctx.pathParam("pearl"));
            send(ctx, ok());
        });
        // webhooks: outside /api, no login, the token is the secret. GET shows a button so link previews load nothing.
        app.get("/hooks/pearl/{token}", ctx -> {
            var hook = activeWebhook(ctx.pathParam("token"));
            ctx.contentType("text/html; charset=utf-8").header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex").result(webhookPage(hook == null ? null : hook.pearl(), null));
        });
        app.post("/hooks/pearl/{token}", ctx -> {
            var token = ctx.pathParam("token");
            var hook = activeWebhook(token);
            boolean form = ctx.contentType() != null && ctx.contentType().startsWith("application/x-www-form-urlencoded");
            PearlService.LoadResult result;
            int status;
            if (hook == null) {
                result = new PearlService.LoadResult(false, "This link doesn't work anymore.");
                status = 404;
            } else if (!pearls.webhookCooldown(token)) {
                result = new PearlService.LoadResult(false, "Already loading. Try again in a few seconds.");
                status = 429;
            } else {
                var username = store.read(s -> s.users.stream().filter(u -> u.id.equals(hook.userId())).findFirst().map(u -> u.username).orElse("?"));
                result = pearls.load(hook.instanceId(), hook.pearl(), "webhook:" + username);
                status = result.ok() ? 200 : 409;
            }
            ctx.header("Cache-Control", "no-store").status(status);
            if (form) {
                ctx.contentType("text/html; charset=utf-8").result(webhookPage(hook == null ? null : hook.pearl(), result));
            } else {
                var json = new JsonObject();
                json.addProperty("ok", result.ok());
                json.addProperty("message", result.message());
                send(ctx, json);
            }
        });

        app.get("/api/instances/{id}/replays", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            var dir = config.instanceDir(id).resolve("replays");
            var list = new JsonArray();
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (var f : files.filter(f -> f.getFileName().toString().endsWith(".mcpr")).sorted(java.util.Comparator.reverseOrder()).toList()) {
                        var json = new JsonObject();
                        json.addProperty("name", f.getFileName().toString());
                        json.addProperty("size", Files.size(f));
                        json.addProperty("modified", Files.getLastModifiedTime(f).toMillis());
                        list.add(json);
                    }
                }
            }
            send(ctx, list);
        });
        app.get("/api/instances/{id}/replays/{name}", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            var name = ctx.pathParam("name");
            if (!name.matches("[A-Za-z0-9_.\\-]+\\.mcpr")) throw ApiException.notFound();
            var file = config.instanceDir(id).resolve("replays").resolve(name);
            if (!Files.isRegularFile(file)) throw ApiException.notFound();
            ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ctx.contentType("application/octet-stream").result(Files.newInputStream(file));
        });
        app.delete("/api/instances/{id}/replays/{name}", ctx -> {
            var id = requireCapability(ctx, Capability.CONTROL).id;
            var name = ctx.pathParam("name");
            if (!name.matches("[A-Za-z0-9_.\\-]+\\.mcpr")) throw ApiException.notFound();
            Files.deleteIfExists(config.instanceDir(id).resolve("replays").resolve(name));
            send(ctx, ok());
        });
    }

    /** A webhook whose owner still exists and still may load pearls on its instance. */
    private PearlService.Webhook activeWebhook(String token) {
        var hook = pearls.resolve(token);
        if (hook == null) return null;
        boolean allowed = store.read(s -> {
            var user = s.users.stream().filter(u -> u.id.equals(hook.userId())).findFirst().orElse(null);
            var instance = s.instances.stream().filter(i -> i.id.equals(hook.instanceId())).findFirst().orElse(null);
            return user != null && instance != null && Permissions.has(Permissions.Identity.of(user), instance, Capability.PEARLS);
        });
        return allowed ? hook : null;
    }

    private static String webhookPage(String pearl, PearlService.LoadResult result) {
        var title = pearl == null ? "Link not found" : "Load " + escapeHtml(pearl) + "'s pearl";
        var message = result != null ? "<p class=\"" + (result.ok() ? "ok" : "err") + "\">" + escapeHtml(result.message()) + "</p>" : "";
        var button = pearl == null ? "<p>This pearl link doesn't work anymore. Get a new one in ZenithHub.</p>"
            : "<form method=\"post\"><button type=\"submit\">Load pearl</button></form>";
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="robots" content="noindex"><title>%s · ZenithHub</title><style>
            body{margin:0;min-height:100dvh;display:grid;place-items:center;background:#09090b;color:#e4e4e7;font:16px system-ui,sans-serif}
            main{width:min(22rem,90vw);text-align:center}h1{font-size:1.25rem;font-weight:600}
            button{width:100%%;padding:.8rem;border:0;border-radius:.6rem;background:#059669;color:#fff;font-size:1rem;font-weight:600;cursor:pointer}
            button:hover{background:#047857}.ok{color:#6ee7b7}.err{color:#fca5a5}p{color:#a1a1aa}
            </style></head><body><main><h1>%s</h1>%s%s</main></body></html>
            """.formatted(title, title, message, button);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean optBoolean(JsonObject body, String key) {
        var e = body.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    // --- manager settings (admin) ---

    private void registerSettingsRoutes() {
        app.get("/api/players/{name}", ctx -> {
            requireUser(ctx);
            var entry = com.zenith.manager.util.MojangProfiles.lookup(ctx.pathParam("name"));
            if (entry == null) throw new ApiException(404, "No Minecraft account named " + ctx.pathParam("name"));
            send(ctx, entry);
        });
        app.get("/api/settings/global", ctx -> {
            requireAdmin(ctx);
            send(ctx, settings.view(SettingsService.Scope.GLOBAL, null));
        });
        app.patch("/api/settings/global", ctx -> {
            requireAdmin(ctx);
            updateSettings(ctx, SettingsService.Scope.GLOBAL, null);
        });
        app.get("/api/servers/{id}/settings", ctx -> {
            requireServerOwner(ctx);
            send(ctx, settings.view(SettingsService.Scope.SERVER, ctx.pathParam("id")));
        });
        app.patch("/api/servers/{id}/settings", ctx -> {
            requireServerOwner(ctx);
            updateSettings(ctx, SettingsService.Scope.SERVER, ctx.pathParam("id"));
        });
        app.get("/api/instances/{id}/settings", ctx -> {
            var id = requireCapability(ctx, Capability.SETTINGS).id;
            send(ctx, settings.view(SettingsService.Scope.INSTANCE, id));
        });
        app.patch("/api/instances/{id}/settings", ctx -> {
            var id = requireCapability(ctx, Capability.SETTINGS).id;
            updateSettings(ctx, SettingsService.Scope.INSTANCE, id);
        });
        app.put("/api/settings/manager", ctx -> {
            requireAdmin(ctx);
            var body = body(ctx);
            store.update(s -> {
                if (body.has("publicAddress")) {
                    var address = body.get("publicAddress").getAsString().trim();
                    if (address.isEmpty()) throw ApiException.badRequest("Public address is required");
                    s.settings.publicAddress = address;
                }
                if (body.has("outboundProxiesEnabled")) s.settings.outboundProxiesEnabled = body.get("outboundProxiesEnabled").getAsBoolean();
            });
            changed();
            send(ctx, ok());
        });
    }

    /** Body: {"set": {path: value}, "reset": [path]}. Responds with the updated settings view. */
    private void updateSettings(Context ctx, SettingsService.Scope scope, String id) throws Exception {
        var body = body(ctx);
        var set = body.has("set") && body.get("set").isJsonObject() ? body.getAsJsonObject("set") : new JsonObject();
        var reset = new java.util.ArrayList<String>();
        if (body.has("reset") && body.get("reset").isJsonArray()) body.getAsJsonArray("reset").forEach(e -> reset.add(e.getAsString()));
        settings.update(scope, id, set, reset);
        send(ctx, settings.view(scope, id));
    }

    // --- users (admin) and sharing ---

    private void registerAccessRoutes() {
        app.get("/api/users", ctx -> {
            requireAdmin(ctx);
            send(ctx, access.listUsers());
        });
        app.post("/api/users", ctx -> {
            requireAdmin(ctx);
            var body = body(ctx);
            var id = access.createUser(requireString(body, "username").trim(), requireString(body, "password"), stringList(body, "mcNames"));
            changed();
            send(ctx, idJson(id));
        });
        app.patch("/api/users/{id}", ctx -> {
            requireAdmin(ctx);
            var body = body(ctx);
            var names = body.has("mcNames") ? stringList(body, "mcNames") : null;
            boolean unlink = body.has("discordId") && body.get("discordId").isJsonNull();
            var id = ctx.pathParam("id");
            var password = optString(body, "password");
            access.updateUser(id, password, names, unlink);
            // a reset password signs the user out everywhere
            if (password != null && !id.equals(requireUser(ctx).id)) sessions.invalidateUser(id);
            changed();
            send(ctx, ok());
        });
        app.delete("/api/users/{id}", ctx -> {
            requireAdmin(ctx);
            var id = ctx.pathParam("id");
            access.deleteUser(id);
            pearls.forget(null, id);
            sessions.invalidateUser(id);
            changed();
            send(ctx, ok());
        });
        // Discord: the admin sets up the bot, everyone may link their own Discord account
        app.get("/api/discord", ctx -> {
            requireAdmin(ctx);
            send(ctx, discord.status());
        });
        app.put("/api/discord", ctx -> {
            requireAdmin(ctx);
            var body = body(ctx);
            if (body.has("token") && !body.get("token").isJsonNull()) {
                var token = body.get("token").getAsString().trim();
                if (!token.matches("[A-Za-z0-9._-]{50,100}")) throw ApiException.badRequest("That doesn't look like a bot token");
                discord.setToken(token);
            }
            if (body.has("enabled")) {
                boolean enabled = body.get("enabled").getAsBoolean();
                store.update(s -> s.settings.discordEnabled = enabled);
            }
            discord.restart();
            send(ctx, discord.status());
        });
        app.delete("/api/discord/token", ctx -> {
            requireAdmin(ctx);
            store.update(s -> s.settings.discordEnabled = false);
            discord.stop();
            discord.clearToken();
            send(ctx, discord.status());
        });
        app.post("/api/me/discord/link", ctx -> {
            var user = requireUser(ctx);
            if (!discord.online()) throw ApiException.badRequest("The Discord bot isn't running");
            var json = new JsonObject();
            json.addProperty("code", discord.createLinkCode(user.id));
            json.addProperty("expiresInSeconds", 600);
            send(ctx, json);
        });
        app.delete("/api/me/discord", ctx -> {
            var user = requireUser(ctx);
            access.updateUser(user.id, null, null, true);
            changed();
            send(ctx, ok());
        });
        // linking your own Minecraft accounts: the lobby shows a code, you enter it here
        app.post("/api/me/minecraft", ctx -> {
            var user = requireUser(ctx);
            var name = access.claimMcLink(user.id, requireString(body(ctx), "code"));
            changed();
            var json = new JsonObject();
            json.addProperty("name", name);
            send(ctx, json);
        });
        app.delete("/api/me/minecraft/{name}", ctx -> {
            access.unlinkMc(requireUser(ctx).id, ctx.pathParam("name"));
            changed();
            send(ctx, ok());
        });
        // anyone may change their own password
        app.post("/api/me/password", ctx -> {
            var user = requireUser(ctx);
            var body = body(ctx);
            if (!Passwords.verify(requireString(body, "currentPassword"), user.passwordHash)) throw ApiException.badRequest("Current password is wrong");
            access.updateUser(user.id, requireString(body, "password"), null, false);
            send(ctx, ok());
        });

        app.get("/api/instances/{id}/access", ctx -> {
            var instance = requireCapability(ctx, Capability.SHARE);
            send(ctx, access.accessView(Permissions.Identity.of(requireUser(ctx)), instance.id));
        });
        app.put("/api/instances/{id}/access", ctx -> {
            var instance = requireCapability(ctx, Capability.SHARE);
            var user = requireUser(ctx);
            var body = body(ctx);
            Role role;
            try {
                role = Role.valueOf(requireString(body, "role"));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown role");
            }
            var extra = java.util.EnumSet.noneOf(Capability.class);
            for (var name : stringList(body, "extra")) {
                try {
                    extra.add(Capability.valueOf(name));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Unknown capability " + name);
                }
            }
            access.putGrant(Permissions.Identity.of(user), instance.id, requireString(body, "subject"), role, extra, "web:" + user.username);
            changed();
            send(ctx, access.accessView(Permissions.Identity.of(user), instance.id));
        });
        app.delete("/api/instances/{id}/access", ctx -> {
            var instance = requireCapability(ctx, Capability.SHARE);
            var user = requireUser(ctx);
            access.removeGrant(Permissions.Identity.of(user), instance.id, requireString(body(ctx), "subject"));
            changed();
            send(ctx, access.accessView(Permissions.Identity.of(user), instance.id));
        });
    }

    private static List<String> stringList(JsonObject body, String key) {
        var e = body.get(key);
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) throw ApiException.badRequest(key + " must be a list");
        var out = new java.util.ArrayList<String>();
        for (var item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) throw ApiException.badRequest(key + " must be a list of strings");
            out.add(item.getAsString());
        }
        return out;
    }

    // --- websocket ---

    private static final class WsClient {
        volatile Permissions.Identity identity;
        final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    }

    private void registerWebSocket() {
        app.ws("/api/ws", ws -> {
            ws.onConnect(ctx -> {
                var origin = ctx.header("Origin");
                var host = ctx.header("X-Forwarded-Host") != null ? ctx.header("X-Forwarded-Host") : ctx.header("Host");
                if (origin != null && host != null && !origin.replaceFirst("^https?://", "").equalsIgnoreCase(host)) {
                    ctx.closeSession(4003, "Bad origin");
                    return;
                }
                var userId = sessions.userId(ctx.cookie(SESSION_COOKIE));
                var user = userId == null ? null : store.read(s -> s.users.stream().filter(u -> u.id.equals(userId)).findFirst().orElse(null));
                if (user == null) {
                    ctx.closeSession(4001, "Not logged in");
                    return;
                }
                ctx.enableAutomaticPings();
                var client = new WsClient();
                client.identity = Permissions.Identity.of(user);
                wsClients.put(ctx, client);
            });
            ws.onMessage(ctx -> {
                var client = wsClients.get(ctx);
                if (client == null) return;
                var msg = JsonParser.parseString(ctx.message()).getAsJsonObject();
                if (msg.has("subscribe")) {
                    client.subscribed.clear();
                    msg.getAsJsonArray("subscribe").forEach(e -> client.subscribed.add(e.getAsString()));
                }
            });
            ws.onClose(wsClients::remove);
            ws.onError(wsClients::remove);
        });
    }

    private void onEvent(EventHub.Event event) {
        if (wsClients.isEmpty()) return;
        var msg = new JsonObject();
        msg.addProperty("type", event.type());
        msg.addProperty("instanceId", event.instanceId());
        msg.add("data", event.payload());
        var text = Json.COMPACT.toJson(msg);
        // resolve the instance once per event, not per client
        State.Instance instance = event.instanceId() == null ? null
            : store.read(s -> s.instances.stream().filter(i -> i.id.equals(event.instanceId())).findFirst().orElse(null));
        wsClients.forEach((ctx, client) -> {
            if (!allowed(client, event, instance)) return;
            try {
                ctx.send(text);
            } catch (Exception e) {
                wsClients.remove(ctx);
            }
        });
    }

    private static boolean allowed(WsClient client, EventHub.Event event, State.Instance instance) {
        var who = client.identity;
        return switch (event.type()) {
            case "changed" -> true;
            case "login" -> who.admin() || who.user().id.equals(str(event.payload(), "ownerId"));
            // only for the Discord bot
            case "notification" -> false;
            case "log" -> instance != null && client.subscribed.contains(instance.id) && Permissions.has(who, instance, Capability.VIEW);
            default -> instance != null && Permissions.has(who, instance, Capability.VIEW);
        };
    }

    /** Tells clients to refetch the overview; cheap and keeps the client logic simple. */
    private void changed() {
        // refresh identities in case users or grants changed
        wsClients.values().forEach(c -> {
            var id = c.identity.user().id;
            store.read(s -> s.users.stream().filter(u -> u.id.equals(id)).findFirst()).ifPresent(u -> c.identity = Permissions.Identity.of(u));
        });
        events.publish("changed", null, new JsonObject());
    }

    // --- views ---

    private JsonObject overview(State.User user) {
        var who = Permissions.Identity.of(user);
        return store.read(s -> {
            var json = new JsonObject();
            json.add("user", userView(user));
            json.addProperty("discordAvailable", discord.online());
            json.addProperty("publicAddress", s.settings.publicAddress);
            var instances = new JsonArray();
            Set<String> visibleServers = new HashSet<>();
            for (var i : s.instances) {
                var caps = Permissions.capabilities(who, i);
                if (!caps.contains(Capability.VIEW)) continue;
                visibleServers.add(i.serverId);
                var v = new JsonObject();
                v.addProperty("id", i.id);
                v.addProperty("name", i.name);
                v.addProperty("accountId", i.accountId);
                v.addProperty("accountName", s.accounts.stream().filter(a -> a.id.equals(i.accountId)).map(a -> a.username).findFirst().orElse("?"));
                v.addProperty("serverId", i.serverId);
                v.addProperty("ownerId", i.ownerId);
                v.addProperty("ownerName", userName(s, i.ownerId));
                v.addProperty("mine", user.id.equals(i.ownerId));
                v.addProperty("sharedWithMe", Permissions.sharedWith(who, i));
                v.addProperty("autostart", i.autostart.name());
                v.addProperty("desiredRunning", i.desiredRunning);
                var capArray = new JsonArray();
                caps.forEach(c -> capArray.add(c.name()));
                v.add("capabilities", capArray);
                v.add("runtime", supervisor.runtime(i.id).toJson());
                instances.add(v);
            }
            json.add("instances", instances);
            var servers = new JsonArray();
            for (var server : s.servers) {
                boolean usable = canUseServer(user, server);
                if (!usable && !visibleServers.contains(server.id)) continue;
                var v = new JsonObject();
                v.addProperty("id", server.id);
                v.addProperty("name", server.name);
                v.addProperty("address", server.address);
                v.addProperty("port", server.port);
                v.addProperty("shared", server.ownerId == null);
                v.addProperty("ownerName", userName(s, server.ownerId));
                v.addProperty("mine", user.id.equals(server.ownerId));
                v.addProperty("usable", usable);
                v.addProperty("editable", who.admin() || user.id.equals(server.ownerId));
                servers.add(v);
            }
            json.add("servers", servers);
            var accountsJson = new JsonArray();
            for (var a : s.accounts) {
                if (!who.admin() && !user.id.equals(a.ownerId)) continue;
                var v = new JsonObject();
                v.addProperty("id", a.id);
                v.addProperty("type", a.type.name());
                v.addProperty("username", a.username);
                v.addProperty("uuid", a.uuid);
                v.addProperty("authError", a.authError);
                v.addProperty("addedAt", a.addedAt);
                v.addProperty("ownerId", a.ownerId);
                v.addProperty("ownerName", userName(s, a.ownerId));
                v.addProperty("mine", user.id.equals(a.ownerId));
                accountsJson.add(v);
            }
            json.add("accounts", accountsJson);
            if (who.admin()) {
                var settings = new JsonObject();
                settings.addProperty("publicAddress", s.settings.publicAddress);
                settings.addProperty("outboundProxiesEnabled", s.settings.outboundProxiesEnabled);
                json.add("settings", settings);
            }
            return json;
        });
    }

    private static String userName(State s, String userId) {
        if (userId == null) return null;
        return s.users.stream().filter(u -> u.id.equals(userId)).map(u -> u.username).findFirst().orElse("deleted user");
    }

    private static String str(JsonObject json, String key) {
        var e = json == null ? null : json.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonObject userView(State.User user) {
        var json = new JsonObject();
        json.addProperty("id", user.id);
        json.addProperty("username", user.username);
        json.addProperty("admin", user.admin);
        var names = new JsonArray();
        user.mcNames.forEach(names::add);
        json.add("mcNames", names);
        json.addProperty("discordLinked", user.discordId != null);
        return json;
    }

    // --- request helpers ---

    private State.User currentUser(Context ctx) {
        var userId = sessions.userId(ctx.cookie(SESSION_COOKIE));
        if (userId == null) return null;
        return store.read(s -> s.users.stream().filter(u -> u.id.equals(userId)).findFirst().orElse(null));
    }

    private State.User requireUser(Context ctx) {
        var user = currentUser(ctx);
        if (user == null) throw new ApiException(401, "Not logged in");
        return user;
    }

    private State.User requireAdmin(Context ctx) {
        var user = requireUser(ctx);
        if (!user.admin) throw ApiException.forbidden();
        return user;
    }

    private State.Instance requireCapability(Context ctx, Capability cap) {
        var who = Permissions.Identity.of(requireUser(ctx));
        var instance = store.read(s -> findInstance(s, ctx.pathParam("id")));
        var caps = Permissions.capabilities(who, instance);
        // don't reveal instances the user can't see at all
        if (!caps.contains(Capability.VIEW)) throw ApiException.notFound();
        if (!caps.contains(cap)) throw ApiException.forbidden();
        return instance;
    }

    /** Renaming and deleting are for the owner (and admins), not for people it's shared with. */
    private State.Instance requireOwner(Context ctx) {
        var user = requireUser(ctx);
        var instance = requireCapability(ctx, Capability.VIEW);
        if (!user.admin && !user.id.equals(instance.ownerId)) throw ApiException.forbidden();
        return instance;
    }

    private void connectQuietly(String instanceId, String actor) {
        try {
            supervisor.connect(instanceId, actor);
        } catch (Exception e) {
            LOG.warn("Connecting instance {} failed: {}", instanceId, e.getMessage());
        }
    }

    private String actor(Context ctx) {
        return "web:" + requireUser(ctx).username;
    }

    private static State.Instance findInstance(State s, String id) {
        return s.instances.stream().filter(i -> i.id.equals(id)).findFirst().orElseThrow(ApiException::notFound);
    }

    private static State.ServerProfile findServer(State s, String id) {
        return s.servers.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow(() -> ApiException.badRequest("Unknown server"));
    }

    private static int freePort(State s, int base, boolean mc) {
        var used = new HashSet<Integer>();
        for (var i : s.instances) used.add(mc ? i.mcPort : i.controlPort);
        int port = base;
        while (used.contains(port)) port++;
        return port;
    }

    private static JsonObject body(Context ctx) {
        try {
            var parsed = JsonParser.parseString(ctx.body());
            if (!parsed.isJsonObject()) throw ApiException.badRequest("Expected a JSON object");
            return parsed.getAsJsonObject();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid JSON");
        }
    }

    private static String requireString(JsonObject body, String key) {
        var value = optString(body, key);
        if (value == null) throw ApiException.badRequest("Missing " + key);
        return value;
    }

    private static String optString(JsonObject body, String key) {
        var e = body.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) throw ApiException.badRequest(key + " must be a string");
        return e.getAsString();
    }

    private static void send(Context ctx, JsonElement json) {
        ctx.contentType("application/json").result(Json.COMPACT.toJson(json));
    }

    private static void error(Context ctx, int status, String message) {
        var json = new JsonObject();
        json.addProperty("error", message);
        ctx.status(status);
        send(ctx, json);
    }

    private static JsonObject ok() {
        var json = new JsonObject();
        json.addProperty("ok", true);
        return json;
    }

    private static JsonObject idJson(String id) {
        var json = new JsonObject();
        json.addProperty("id", id);
        return json;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
