package com.zenith.manager.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** What {@link Boot} did, for the manager's plugin page. Loaded by the parent class loader, so both sides share it. */
public final class BootInfo {
    /** Plugins whose manager side is in this process: id → revision. */
    public static final Map<String, String> LOADED = new ConcurrentHashMap<>();
    public static final Map<String, PluginManifest> MANIFESTS = new ConcurrentHashMap<>();
    /** Problems per plugin id; "" for ones no plugin can be blamed for. */
    public static final Map<String, List<String>> ERRORS = new ConcurrentHashMap<>();
    public static volatile String safeMode;

    private BootInfo() {}

    public static void error(String pluginId, String message) {
        ERRORS.computeIfAbsent(pluginId == null ? "" : pluginId, k -> new CopyOnWriteArrayList<>()).add(message);
    }

    static Path attemptsFile(Path dataDir) {
        return dataDir.resolve("manager").resolve("plugins-boot.txt");
    }

    /** Starts with plugins that didn't finish; two in a row and the next start runs without plugins. */
    static int failedStarts(Path dataDir) {
        try {
            return Integer.parseInt(Files.readString(attemptsFile(dataDir)).strip());
        } catch (Exception e) {
            return 0;
        }
    }

    static void setFailedStarts(Path dataDir, int count) {
        try {
            Files.createDirectories(attemptsFile(dataDir).getParent());
            Files.writeString(attemptsFile(dataDir), Integer.toString(count));
        } catch (IOException e) {
            System.err.println("Couldn't write " + attemptsFile(dataDir) + ": " + e);
        }
    }

    /** The manager is up: the plugins didn't break the start. */
    public static void started(Path dataDir) {
        if (Files.exists(attemptsFile(dataDir))) setFailedStarts(dataDir, 0);
    }
}
