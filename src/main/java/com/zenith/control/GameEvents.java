package com.zenith.control;

import com.google.gson.JsonObject;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.module.NoTotemsEvent;
import com.zenith.event.module.PlayerHealthChangedEvent;
import com.zenith.event.module.ServerPlayerAttackedUsEvent;
import com.zenith.event.module.ServerPlayerInVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLeftVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLogoutInVisualRangeEvent;
import com.zenith.event.module.TotemPopEvent;
import com.zenith.event.queue.QueueCompleteEvent;
import com.zenith.event.server.ServerRestartingEvent;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * ZenithHub: what happens to the bot in game, as structured events for integrations (like Blockbot) through the
 * manager. Every event is {@code {"type":"game","event":<kind>,"ts":…}} plus its own fields.
 *
 * <p>These are independent of Zenith's modules and their Discord alerts: visual range works with the VisualRange
 * module off, and so on.
 */
public final class GameEvents {
    public static final GameEvents INSTANCE = new GameEvents();

    private GameEvents() {}

    public void start() {
        EVENT_BUS.subscribe(
            this,
            of(TotemPopEvent.class, this::onTotem),
            of(NoTotemsEvent.class, e -> publish(event("noTotems"))),
            of(ServerPlayerAttackedUsEvent.class, this::onAttacked),
            of(PlayerHealthChangedEvent.class, this::onHealth),
            of(ClientDeathEvent.class, e -> publish(withPosition(event("death")))),
            of(ServerPlayerInVisualRangeEvent.class, e -> onVisualRange("enter", e.playerEntry(), e.playerEntity())),
            of(ServerPlayerLeftVisualRangeEvent.class, e -> onVisualRange("leave", e.playerEntry(), e.playerEntity())),
            of(ServerPlayerLogoutInVisualRangeEvent.class, e -> onVisualRange("logout", e.playerEntry(), e.playerEntity())),
            of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onChat),
            of(QueueCompleteEvent.class, e -> {
                var json = event("queueComplete");
                json.addProperty("seconds", e.queueDuration().toSeconds());
                publish(json);
            }),
            of(ServerRestartingEvent.class, e -> {
                var json = event("serverRestarting");
                json.addProperty("message", e.message());
                publish(json);
            })
        );
    }

    private void onTotem(TotemPopEvent e) {
        if (e.entityId() != CACHE.getPlayerCache().getEntityId()) return;
        // the inventory update for the used totem arrives right after the pop
        EXECUTOR.schedule(() -> {
            var json = withPosition(event("totem"));
            json.addProperty("totemsLeft", totems());
            json.addProperty("health", health());
            publish(json);
        }, 150, TimeUnit.MILLISECONDS);
    }

    private void onAttacked(ServerPlayerAttackedUsEvent e) {
        var json = withPosition(event("attacked"));
        player(json, "attacker", CACHE.getTabListCache().get(e.attacker().getUuid()).orElse(null), e.attacker());
        json.addProperty("health", health());
        publish(json);
    }

    /** Only when the shown hearts change, not for every regen tick. */
    private void onHealth(PlayerHealthChangedEvent e) {
        if (Math.round(e.newHealth()) == Math.round(e.previousHealth())) return;
        var json = event("health");
        json.addProperty("health", Math.round(e.newHealth() * 10) / 10.0);
        json.addProperty("previous", Math.round(e.previousHealth() * 10) / 10.0);
        publish(json);
    }

    private void onVisualRange(String action, PlayerListEntry entry, EntityPlayer entity) {
        var json = event("visualRange");
        json.addProperty("action", action);
        player(json, "player", entry, entity);
        if (entity != null) {
            json.addProperty("x", Math.floor(entity.getX()));
            json.addProperty("y", Math.floor(entity.getY()));
            json.addProperty("z", Math.floor(entity.getZ()));
            json.addProperty("friend", PLAYER_LISTS.getFriendsList().contains(entity.getUuid()));
        }
        publish(json);
    }

    private void onWhisper(WhisperChatEvent e) {
        if (e.outgoing() || e.sender() == null) return;
        var json = event("whisper");
        json.addProperty("from", e.sender().getName());
        json.addProperty("uuid", String.valueOf(e.sender().getProfileId()));
        json.addProperty("message", e.message());
        publish(json);
    }

    private void onChat(PublicChatEvent e) {
        var json = event("chat");
        if (e.sender() != null) {
            json.addProperty("from", e.sender().getName());
            json.addProperty("uuid", String.valueOf(e.sender().getProfileId()));
        }
        json.addProperty("message", e.message());
        publish(json);
    }

    // --- helpers ---

    private static JsonObject event(String kind) {
        var json = new JsonObject();
        json.addProperty("type", "game");
        json.addProperty("event", kind);
        json.addProperty("ts", System.currentTimeMillis());
        return json;
    }

    private static JsonObject withPosition(JsonObject json) {
        var cache = CACHE.getPlayerCache();
        json.addProperty("x", Math.floor(cache.getX()));
        json.addProperty("y", Math.floor(cache.getY()));
        json.addProperty("z", Math.floor(cache.getZ()));
        return json;
    }

    private static void player(JsonObject json, String key, PlayerListEntry entry, EntityPlayer entity) {
        var name = entry != null ? entry.getName() : null;
        json.addProperty(key, name != null ? name : "?");
        var uuid = entity != null && entity.getUuid() != null ? entity.getUuid() : entry != null ? entry.getProfileId() : null;
        if (uuid != null) json.addProperty(key + "Uuid", uuid.toString());
    }

    private static double health() {
        return Math.round(CACHE.getPlayerCache().getThePlayer().getHealth() * 10) / 10.0;
    }

    private static int totems() {
        var count = 0;
        for (ItemStack item : CACHE.getPlayerCache().getPlayerInventory()) {
            if (item != Container.EMPTY_STACK && item.getId() == ItemRegistry.TOTEM_OF_UNDYING.id()) count += item.getAmount();
        }
        return count;
    }

    private static void publish(JsonObject json) {
        ControlServer.INSTANCE.publish(json);
    }
}
