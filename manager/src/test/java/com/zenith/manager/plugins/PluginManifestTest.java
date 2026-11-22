package com.zenith.manager.plugins;

import com.zenith.manager.boot.PluginManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestTest {
    @TempDir
    Path dir;

    private void write(String path, String content) throws IOException {
        var file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private IllegalArgumentException invalid() {
        return assertThrows(IllegalArgumentException.class, () -> PluginManifest.read(dir));
    }

    @Test
    void readsAFullManifest() throws IOException {
        write("plugin.json", """
            {"id": "hello", "name": "Hello", "version": "1.0.0", "authors": ["a"],
             "manager": {"main": "x.Manager", "mixins": ["x.M"]},
             "bot": {"main": "x.Bot"},
             "web": {},
             "settings": [
               {"key": "greeting", "label": "Greeting", "type": "string", "default": "Hi", "public": true},
               {"key": "token", "label": "Token", "type": "secret", "public": true},
               {"key": "mode", "label": "Mode", "type": "select", "options": [{"value": "a", "label": "A"}]}
             ]}""");
        write("manager/m.jar", "");
        write("bot/lib/b.jar", "");
        write("web/index.js", "");
        write("web/index.css", "");
        var m = PluginManifest.read(dir);
        assertEquals("hello", m.id());
        assertEquals(java.util.List.of("manager/m.jar"), m.manager().jars());
        assertEquals(java.util.List.of("bot/lib/b.jar"), m.bot().jars());
        assertEquals(java.util.List.of("x.M"), m.manager().mixins());
        // an empty "web" still finds the default files
        assertEquals(java.util.List.of("web/index.js"), m.web().scripts());
        assertEquals(java.util.List.of("web/index.css"), m.web().styles());
        assertTrue(m.settings().get(0).isPublic());
        // secrets never go to browsers, even when marked public
        assertFalse(m.settings().get(1).isPublic());
    }

    @Test
    void webOnlyPluginNeedsNoJars() throws IOException {
        write("plugin.json", "{\"id\": \"w\", \"name\": \"W\", \"version\": \"1\"}");
        write("web/index.js", "");
        var m = PluginManifest.read(dir);
        assertFalse(m.manager().present());
        assertFalse(m.bot().present());
        assertTrue(m.web().present());
    }

    @Test
    void rejectsBadManifests() throws IOException {
        write("web/index.js", "");
        write("plugin.json", "{\"id\": \"Bad Id\", \"name\": \"x\", \"version\": \"1\"}");
        invalid();
        write("plugin.json", "{\"id\": \"ok\", \"name\": \"x\", \"version\": \"1\", \"web\": {\"scripts\": [\"../../etc/passwd\"]}}");
        invalid();
        write("plugin.json", "{\"id\": \"ok\", \"name\": \"x\", \"version\": \"1\", \"web\": {\"scripts\": [\"plugin.json\"]}}");
        invalid();
        write("plugin.json", "{\"id\": \"ok\", \"name\": \"x\", \"version\": \"1\", \"bot\": {\"main\": \"x.Bot\"}}");
        invalid();
        write("plugin.json", "{\"id\": \"ok\", \"name\": \"x\", \"version\": \"1\", \"settings\": [{\"key\": \"a\", \"label\": \"A\", \"type\": \"color\"}]}");
        invalid();
        write("plugin.json", "{\"id\": \"ok\", \"version\": \"1\"}");
        invalid();
    }

    @Test
    void rejectsPluginsWithNothingToRun() throws IOException {
        write("plugin.json", "{\"id\": \"ok\", \"name\": \"x\", \"version\": \"1\"}");
        invalid();
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void unzipStripsASingleTopFolder() throws IOException {
        var files = new LinkedHashMap<String, String>();
        files.put("hello/plugin.json", "{}");
        files.put("hello/web/index.js", "x");
        files.put("__MACOSX/hello/._plugin.json", "");
        PluginService.unzip(new ByteArrayInputStream(zip(files)), dir);
        assertEquals(dir.resolve("hello"), PluginService.rootOf(dir));
    }

    @Test
    void unzipRejectsPathsOutsideTheFolder() throws IOException {
        var target = dir.resolve("out");
        Files.createDirectories(target);
        assertThrows(IllegalArgumentException.class,
            () -> PluginService.unzip(new ByteArrayInputStream(zip(Map.of("../evil.txt", "x"))), target));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
        assertThrows(IllegalArgumentException.class,
            () -> PluginService.unzip(new ByteArrayInputStream(zip(Map.of("/abs.txt", "x"))), target));
    }

    @Test
    void unzipRejectsNonZips() {
        assertThrows(IllegalArgumentException.class,
            () -> PluginService.unzip(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)), dir));
    }

    @Test
    void rootNeedsAManifest() throws IOException {
        PluginService.unzip(new ByteArrayInputStream(zip(Map.of("a/b.txt", "x", "c.txt", "y"))), dir);
        assertThrows(IllegalArgumentException.class, () -> PluginService.rootOf(dir));
    }
}
