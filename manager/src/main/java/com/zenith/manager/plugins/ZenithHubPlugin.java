package com.zenith.manager.plugins;

import com.google.gson.JsonObject;

/**
 * A plugin's manager side, named by {@code manager.main} in its {@code plugin.json}, made with a public
 * no-argument constructor. There is no sandbox: through {@link PluginContext#hub()} a plugin reaches every
 * service of the manager, it can add and replace web routes, and with mixins ({@code manager.mixins}) it can change
 * any class. See docs/plugins.md.
 */
public interface ZenithHubPlugin {
    /** Before the web server and the lobby start. Register routes and listeners here. */
    void onLoad(PluginContext context) throws Exception;

    /** Everything is running: the web server, the lobby, the Discord bot, the instances being restored. */
    default void onStarted() throws Exception {}

    /** The admin saved the plugin's settings. */
    default void onSettingsChanged(JsonObject settings) throws Exception {}

    /** The manager is shutting down. */
    default void onStop() {}
}
