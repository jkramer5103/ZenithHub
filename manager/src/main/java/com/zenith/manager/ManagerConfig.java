package com.zenith.manager;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Process-level configuration from environment variables. Everything else lives in the state file.
 */
public record ManagerConfig(
    Path dataDir,
    int webPort,
    String webBind,
    Path webDir,
    List<String> zenithCommand,
    int instancePortBase,
    int controlPortBase,
    int lobbyPort,
    /** Off only for local testing: lets unauthenticated clients into the lobby. */
    boolean lobbyOnlineMode,
    /** ZENITH_DISABLE_CHEATS: turns off KillAura, AutoEat, AntiAFK and the other cheat modules everywhere. */
    boolean cheatsDisabled
) {
    public static ManagerConfig fromEnv() {
        var env = System.getenv();
        return new ManagerConfig(
            Path.of(env.getOrDefault("ZENITH_DATA_DIR", "/data")).toAbsolutePath(),
            Integer.parseInt(env.getOrDefault("ZENITH_WEB_PORT", "8080")),
            env.getOrDefault("ZENITH_WEB_BIND", "0.0.0.0"),
            Path.of(env.getOrDefault("ZENITH_WEB_DIR", "/app/web")).toAbsolutePath(),
            // e.g. "/app/zenith/ZenithProxy" (native) or "java -jar /app/zenith/ZenithProxy.jar"
            Arrays.stream(env.getOrDefault("ZENITH_COMMAND", "/app/zenith/ZenithProxy").trim().split("\\s+")).toList(),
            Integer.parseInt(env.getOrDefault("ZENITH_INSTANCE_PORT_BASE", "30000")),
            Integer.parseInt(env.getOrDefault("ZENITH_CONTROL_PORT_BASE", "40000")),
            Integer.parseInt(env.getOrDefault("ZENITH_LOBBY_PORT", "25565")),
            !"false".equalsIgnoreCase(env.get("ZENITH_LOBBY_ONLINE_MODE")),
            com.zenith.manager.instances.Cheats.isTruthy(env.get("ZENITH_DISABLE_CHEATS"))
        );
    }

    public Path stateFile() {
        return dataDir.resolve("manager").resolve("state.json");
    }

    public Path zenithDefaultsFile() {
        return dataDir.resolve("manager").resolve("zenith-defaults.json");
    }

    public Path zenithSchemaFile() {
        return dataDir.resolve("manager").resolve("zenith-schema.json");
    }

    public Path accountDir(String accountId) {
        return dataDir.resolve("accounts").resolve(accountId);
    }

    public Path instanceDir(String instanceId) {
        return dataDir.resolve("instances").resolve(instanceId);
    }
}
