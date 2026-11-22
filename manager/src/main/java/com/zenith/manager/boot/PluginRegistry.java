package com.zenith.manager.boot;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Which plugins are installed and on: {@code /data/manager/plugins.json}. A plugin's files are in
 * {@code /data/plugins/<id>/<revision>/}; every install or update gets a new revision, so the running manager and
 * bots keep their jars until they restart.
 */
public final class PluginRegistry {
    public static final class Entry {
        public String id;
        public boolean enabled;
        public String revision;
        public long installedAt;
        public long updatedAt;
    }

    private static final com.google.gson.Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PluginRegistry() {}

    public static Path file(Path dataDir) {
        return dataDir.resolve("manager").resolve("plugins.json");
    }

    public static Path pluginsDir(Path dataDir) {
        return dataDir.resolve("plugins");
    }

    public static Path codeDir(Path dataDir, Entry entry) {
        return pluginsDir(dataDir).resolve(entry.id).resolve(entry.revision);
    }

    public static List<Entry> read(Path dataDir) throws IOException {
        var file = file(dataDir);
        if (!Files.exists(file)) return new ArrayList<>();
        var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!root.has("plugins")) return new ArrayList<>();
        List<Entry> list = GSON.fromJson(root.get("plugins"), new TypeToken<List<Entry>>() {}.getType());
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static void write(Path dataDir, List<Entry> entries) throws IOException {
        var file = file(dataDir);
        Files.createDirectories(file.getParent());
        var root = new com.google.gson.JsonObject();
        root.add("plugins", GSON.toJsonTree(entries));
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
