package com.zenith.control.plugins;

/**
 * A ZenithHub plugin's bot side, named by {@code bot.main} in its {@code plugin.json}. One object per instance
 * process, made with a public no-argument constructor once ZenithProxy has started.
 *
 * <p>There is no sandbox: a plugin can use everything in ZenithProxy ({@code com.zenith.Globals}: the event bus,
 * modules, commands, the cache, the client and server sessions…) and change any class with mixins
 * ({@code bot.mixins}). See docs/plugins.md.
 */
public interface ZenithHubBotPlugin {
    void onLoad(BotPluginContext context) throws Exception;

    /** The admin changed the plugin's settings while the instance was running. */
    default void onSettingsChanged(com.google.gson.JsonObject settings) {}

    /** The process is shutting down. */
    default void onStop() {}
}
