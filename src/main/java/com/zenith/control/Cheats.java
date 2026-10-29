package com.zenith.control;

import com.zenith.command.api.Command;
import com.zenith.command.impl.*;
import com.zenith.module.api.Module;
import com.zenith.module.impl.*;

import java.util.Set;

import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.DEFAULT_LOG;

/**
 * ZENITH_DISABLE_CHEATS: turns off every module that plays the game for you or gives an unfair advantage.
 * Their commands don't exist and the modules can't be enabled by config, commands or tasks.
 *
 * Pathfinder, PearlLoader, AutoDisconnect, VisualRange and Tasks stay available.
 * Keep this list in sync with the manager's Cheats class.
 */
public final class Cheats {
    private static final boolean DISABLED = isTruthy(System.getenv("ZENITH_DISABLE_CHEATS"));

    private static final Set<Class<? extends Module>> MODULES = Set.of(
        KillAura.class, SpawnPatrol.class, AutoTotem.class, AutoArmor.class, AutoMend.class, Spook.class,
        AntiAFK.class, AutoFish.class, AutoEat.class, AutoOmen.class, AutoDrop.class, AutoRespawn.class,
        Spammer.class, AutoReply.class
    );

    private static final Set<Class<? extends Command>> COMMANDS = Set.of(
        KillAuraCommand.class, SpawnPatrolCommand.class, TickRateCommand.class, AutoTotemCommand.class,
        AutoArmorCommand.class, AutoMendCommand.class, SpookCommand.class, AntiAFKCommand.class,
        AutoFishCommand.class, AutoEatCommand.class, AutoOmenCommand.class, AutoDropCommand.class,
        AutoRespawnCommand.class, SpammerCommand.class, AutoReplyCommand.class
    );

    private Cheats() {}

    public static boolean disabled() {
        return DISABLED;
    }

    public static boolean blocks(Module module) {
        return DISABLED && MODULES.contains(module.getClass());
    }

    public static boolean blocks(Command command) {
        return DISABLED && COMMANDS.contains(command.getClass());
    }

    /** Called early in startup, so the saved config never claims a cheat is on. */
    public static void applyOverrides() {
        if (!DISABLED) return;
        var extra = CONFIG.client.extra;
        extra.killAura.enabled = false;
        extra.spawnPatrol.enabled = false;
        extra.autoTotem.enabled = false;
        extra.autoArmor.enabled = false;
        extra.autoMend.enabled = false;
        extra.spook.enabled = false;
        extra.antiafk.enabled = false;
        extra.autoFish.enabled = false;
        extra.autoEat.enabled = false;
        extra.autoOmen.enabled = false;
        extra.autoDrop.enabled = false;
        extra.autoRespawn.enabled = false;
        extra.spammer.enabled = false;
        extra.autoReply.enabled = false;
        CONFIG.client.tickRate = 1.0;
        DEFAULT_LOG.info("Cheats are disabled (ZENITH_DISABLE_CHEATS)");
    }

    private static boolean isTruthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes"));
    }
}
