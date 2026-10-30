package com.zenith.manager.instances;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.zenith.manager.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZENITH_DISABLE_CHEATS: the modules Zenith turns off (see com.zenith.control.Cheats).
 * Their settings are forced off and left out of the settings pages.
 */
public final class Cheats {
    private static final List<String> MODULES = List.of(
        "killAura", "spawnPatrol", "autoTotem", "autoArmor", "autoMend", "spook",
        "antiafk", "autoFish", "autoEat", "autoOmen", "autoDrop", "autoRespawn",
        "spammer", "autoReply"
    );

    private Cheats() {}

    /** Whether a setting belongs to a disabled cheat. */
    public static boolean covers(String path) {
        if (path.equals("client.tickRate")) return true;
        return MODULES.stream().anyMatch(m -> path.startsWith("client.extra." + m + "."));
    }

    public static Map<String, JsonElement> forcedValues() {
        var forced = new LinkedHashMap<String, JsonElement>();
        MODULES.forEach(m -> forced.put("client.extra." + m + ".enabled", new JsonPrimitive(false)));
        forced.put("client.tickRate", new JsonPrimitive(1.0));
        return forced;
    }

    /**
     * ZenithHub's defaults on top of Zenith's: every cheat module starts off, so an account only plays
     * like a client once its owner turns that on. Any layer can still enable them.
     */
    public static JsonObject offByDefault(JsonObject zenithDefaults) {
        var defaults = zenithDefaults.deepCopy();
        MODULES.forEach(m -> Json.set(defaults, "client.extra." + m + ".enabled", new JsonPrimitive(false)));
        return defaults;
    }

    public static boolean isTruthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes"));
    }
}
