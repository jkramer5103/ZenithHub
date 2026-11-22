package com.zenith.control.plugins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.nio.file.Path;
import java.util.Map;

/** What ZenithHub gives a plugin's bot side, besides everything in ZenithProxy itself. */
public interface BotPluginContext {
    String id();

    String version();

    /** The instance this process runs (its ZenithHub id). */
    String instanceId();

    /** The plugin's settings as the admin set them on the Plugins page, secrets included. */
    JsonObject settings();

    /** A folder for this plugin in this instance's directory; kept across restarts and plugin updates. */
    Path dataDir();

    ComponentLogger logger();

    /**
     * Sends an event to the manager: the plugin's manager side gets it, and so does the web UI as a {@code plugin}
     * WebSocket message for everyone who can see the instance.
     */
    void emit(String event, JsonElement data);

    /**
     * Answers {@code /v1/plugin/<id>/<path>} on the control API. The manager reaches it, and the web UI through
     * {@code /api/plugins/<id>/instances/<instance>/bot/<path>} (GET needs VIEW, everything else CONTROL).
     */
    void route(String path, RouteHandler handler);

    @FunctionalInterface
    interface RouteHandler {
        JsonElement handle(Request request) throws Exception;
    }

    /** {@code body} is null for requests without one. */
    record Request(String method, String path, Map<String, String> query, JsonElement body) {}
}
