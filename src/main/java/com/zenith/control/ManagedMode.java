package com.zenith.control;

import com.zenith.plugin.DefaultGsonConfigSerializer;
import com.zenith.util.config.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static com.zenith.Globals.*;

/**
 * Managed mode: this process is one instance supervised by the ZenithProxy manager.
 *
 * The manager owns account login, Discord, updates, and user input.
 * Zenith exposes a localhost control API and logs to stdout.
 */
public final class ManagedMode {
    public static final File DEFAULTS_FILE = new File("config.defaults.json");
    public static final File SCHEMA_FILE = new File("config.schema.json");
    private static final boolean MANAGED = System.getenv("ZENITH_MANAGED") != null;

    private ManagedMode() {}

    /**
     * ZENITH_DUMP_SCHEMA: write the config defaults and schema to the working directory and exit.
     * Lets the manager build settings pages before any instance has run.
     */
    public static void dumpSchemaIfRequested() {
        if (System.getenv("ZENITH_DUMP_SCHEMA") == null) return;
        writeDefaults();
        System.exit(0);
    }

    public static boolean isManaged() {
        return MANAGED;
    }

    /**
     * Called early in startup, before any subsystem reads these settings.
     */
    public static void applyOverrides() {
        if (!MANAGED) return;
        CONFIG.interactiveTerminal.enable = false;
        CONFIG.interactiveTerminal.logToDiscord = false;
        CONFIG.discord.enable = false;
        CONFIG.authentication.openBrowserOnLogin = false;
        LAUNCH_CONFIG.auto_update = false;
        LAUNCH_CONFIG.auto_update_launcher = false;
        writeDefaults();
    }

    public static void startControlServer() {
        if (!MANAGED) return;
        var portEnv = System.getenv("ZENITH_CONTROL_PORT");
        var token = System.getenv("ZENITH_CONTROL_TOKEN");
        if (portEnv == null || token == null || token.isBlank()) {
            DEFAULT_LOG.error("Managed mode requires ZENITH_CONTROL_PORT and ZENITH_CONTROL_TOKEN");
            System.exit(69);
            return;
        }
        try {
            ControlServer.INSTANCE.start(Integer.parseInt(portEnv), token);
        } catch (final Exception e) {
            DEFAULT_LOG.error("Failed starting control server", e);
            System.exit(1);
        }
    }

    // the manager diffs instance config.json against these defaults to compute instance overrides
    private static void writeDefaults() {
        try {
            var tmp = new File(DEFAULTS_FILE.getPath() + ".tmp");
            try (Writer out = new FileWriter(tmp)) {
                DefaultGsonConfigSerializer.INSTANCE.write(new Config(), out);
            }
            Files.move(tmp.toPath(), DEFAULTS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            var schemaTmp = new File(SCHEMA_FILE.getPath() + ".tmp");
            Files.writeString(schemaTmp.toPath(), GSON.toJson(ConfigReflection.schema(new Config())));
            Files.move(schemaTmp.toPath(), SCHEMA_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            DEFAULT_LOG.error("Failed writing {}", DEFAULTS_FILE, e);
        }
    }
}
