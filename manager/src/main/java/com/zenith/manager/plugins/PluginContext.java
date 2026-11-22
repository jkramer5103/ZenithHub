package com.zenith.manager.plugins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.boot.PluginManifest;
import com.zenith.manager.state.State;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.function.Consumer;

/** What ZenithHub gives a plugin's manager side. */
public interface PluginContext {
    String id();

    PluginManifest manifest();

    /** The plugin's unpacked zip. Replaced on every update, so keep data in {@link #dataDir()}. */
    Path directory();

    /** {@code /data/plugin-data/<id>}: kept across updates, deleted only if the admin removes the plugin with its data. */
    Path dataDir();

    /** The settings from the Plugins page, secrets included. */
    JsonObject settings();

    Logger logger();

    /** Every service of the manager. */
    Hub hub();

    /** Changes the web server's configuration before it is created. Only during {@code onLoad}. */
    void javalin(Consumer<JavalinConfig> configure);

    /**
     * Adds routes, before-handlers and the like. They are registered ahead of ZenithHub's own, so a {@code before}
     * handler that calls {@code ctx.skipRemainingHandlers()} can replace a built-in route. Only during {@code onLoad}.
     * Use {@code /api/plugins/<id>/…} for the plugin's own API.
     */
    void routes(Consumer<Javalin> register);

    /** The signed-in user of a request (cookie or integration token), or null. */
    State.User user(Context ctx);

    /** The signed-in user, or a 401. */
    State.User requireUser(Context ctx);

    /** A signed-in admin (not an integration token), or a 403. */
    State.User requireAdmin(Context ctx);

    /** The instance, if the request's user has the capability on it; otherwise a 404 or 403. */
    State.Instance requireCapability(Context ctx, String instanceId, Capability capability);

    /** Events the plugin's bot side sent with {@code emit}. */
    void onBotEvent(BotEventListener listener);

    @FunctionalInterface
    interface BotEventListener {
        void onEvent(String instanceId, String event, JsonElement data);
    }

    /**
     * Sends a {@code plugin} WebSocket message to the web UI: to everyone who can see the instance, or with a null
     * instance id to everyone signed in.
     */
    void publish(String event, String instanceId, JsonElement data);

    /** Calls a route of the plugin's bot side ({@code /v1/plugin/<id>/<path>}) in a running instance. */
    HttpResponse<String> callBot(String instanceId, String method, String path, JsonElement body) throws Exception;
}
