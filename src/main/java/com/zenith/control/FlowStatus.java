package com.zenith.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.player.World;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static com.zenith.Globals.*;

/**
 * ZenithHub: the parts of {@code /v1/status} that flows watch (entities nearby, the inventory, what the
 * pathfinder does), so the manager can turn their changes into triggers.
 */
final class FlowStatus {
    /** How far entities are reported, and how many at most (the nearest). */
    static final double ENTITY_RANGE = 64;
    static final int MAX_ENTITIES = 40;

    /** Mobs that attack on sight, by registry name. */
    private static final Set<String> HOSTILE = Set.of(
        "zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton", "creeper", "spider",
        "cave_spider", "enderman", "endermite", "silverfish", "witch", "slime", "magma_cube", "blaze", "ghast", "phantom",
        "pillager", "vindicator", "evoker", "ravager", "vex", "guardian", "elder_guardian", "shulker", "hoglin", "zoglin",
        "piglin_brute", "warden", "breeze", "creaking", "wither", "ender_dragon", "illusioner");
    private static final Set<String> VEHICLES = Set.of("minecart", "chest_minecart", "furnace_minecart", "hopper_minecart", "tnt_minecart",
        "command_block_minecart", "spawner_minecart");

    private FlowStatus() {}

    static void add(JsonObject json) {
        var cache = CACHE.getPlayerCache();
        var self = cache.getThePlayer();
        json.addProperty("alive", cache.isAlive());
        if (json.get("position") instanceof JsonObject pos) {
            pos.addProperty("yaw", Math.round(cache.getYaw() * 10) / 10.0);
            pos.addProperty("pitch", Math.round(cache.getPitch() * 10) / 10.0);
        }
        var pathing = new JsonObject();
        boolean active = BARITONE.isActive();
        pathing.addProperty("active", active);
        if (active) BARITONE.getPathingControlManager().mostRecentInControl().ifPresent(p -> pathing.addProperty("process", p.displayName()));
        json.add("pathing", pathing);
        json.add("entities", entities(self));
        inventory(json);
        var open = cache.getInventoryCache().getOpenContainer();
        if (open != null && open.getContainerId() != 0) {
            var container = new JsonObject();
            container.addProperty("title", open.getTitle() != null ? com.zenith.util.ComponentSerializer.serializePlain(open.getTitle()) : "");
            container.addProperty("size", open.getSize());
            json.add("container", container);
        }
        json.addProperty("raining", CACHE.getChunkCache().isRaining());
    }

    /** The nearest entities other than ourselves, players and mobs alike. */
    private static JsonArray entities(Entity self) {
        record Seen(Entity entity, double distance) {}
        double range = ENTITY_RANGE * ENTITY_RANGE;
        // distances once: the netty thread moves entities mid-sort
        var seen = CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e != self && !(e instanceof EntityPlayer p && (p.isSelfPlayer() || self.getUuid() != null && self.getUuid().equals(p.getUuid()))))
            .map(e -> new Seen(e, distanceSq(self, e)))
            .filter(s -> s.distance <= range)
            .sorted(Comparator.comparingDouble(Seen::distance))
            .limit(MAX_ENTITIES)
            .toList();
        var out = new JsonArray();
        for (var s : seen) {
            var e = s.entity;
            var json = new JsonObject();
            var data = e.getEntityData();
            var type = data != null ? data.name() : String.valueOf(e.getEntityType()).toLowerCase(java.util.Locale.ROOT);
            json.addProperty("type", type);
            json.addProperty("kind", kind(e, type, data != null && data.livingEntity()));
            if (e instanceof EntityPlayer p) {
                json.addProperty("name", CACHE.getTabListCache().get(p.getUuid()).map(PlayerListEntry::getName).orElse(null));
                json.addProperty("friend", p.getUuid() != null && PLAYER_LISTS.getFriendsList().contains(p.getUuid()));
            }
            json.addProperty("id", e.getEntityId());
            json.addProperty("x", Math.round(e.getX() * 10) / 10.0);
            json.addProperty("y", Math.round(e.getY() * 10) / 10.0);
            json.addProperty("z", Math.round(e.getZ() * 10) / 10.0);
            json.addProperty("distance", Math.round(Math.sqrt(s.distance) * 10) / 10.0);
            out.add(json);
        }
        return out;
    }

    private static String kind(Entity e, String type, boolean living) {
        if (e instanceof EntityPlayer) return "player";
        if (HOSTILE.contains(type)) return "hostile";
        if (type.equals("item") || type.equals("experience_orb")) return "item";
        if (VEHICLES.contains(type) || type.endsWith("_boat") || type.endsWith("_raft") || type.endsWith("_chest_boat")) return "vehicle";
        return living ? "mob" : "other";
    }

    /** Item counts of the player's own inventory, free slots, and how worn the equipped tools and armour are. */
    private static void inventory(JsonObject json) {
        var cache = CACHE.getPlayerCache();
        var inventory = cache.getInventoryCache().getPlayerInventory();
        if (inventory == null) return;
        var items = new TreeMap<String, Integer>();
        int free = 0;
        // 9–44: the main inventory and the hotbar, 45: the off hand
        for (int i = 9; i <= 45 && i < inventory.getSize(); i++) {
            var stack = inventory.getItemStack(i);
            if (stack == Container.EMPTY_STACK) {
                if (i <= 44) free++;
                continue;
            }
            items.merge(name(stack), stack.getAmount(), Integer::sum);
        }
        for (int i = 5; i <= 8; i++) {
            var stack = inventory.getItemStack(i);
            if (stack != Container.EMPTY_STACK) items.merge(name(stack), stack.getAmount(), Integer::sum);
        }
        var counts = new JsonObject();
        items.forEach(counts::addProperty);
        json.add("items", counts);
        json.addProperty("freeSlots", free);
        var held = cache.getEquipment(EquipmentSlot.MAIN_HAND);
        json.addProperty("held", held == Container.EMPTY_STACK ? "" : name(held));
        var worn = new JsonArray();
        for (var slot : List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE,
            EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS)) {
            var stack = cache.getEquipment(slot);
            if (stack == Container.EMPTY_STACK) continue;
            var data = ItemRegistry.REGISTRY.get(stack.getId());
            if (data == null) continue;
            var components = stack.withAddedComponents(data.components()).getDataComponentsOrEmpty();
            Integer max = components.get(DataComponentTypes.MAX_DAMAGE);
            if (max == null || max <= 0 || components.contains(DataComponentTypes.UNBREAKABLE)) continue;
            int damage = components.getOrDefault(DataComponentTypes.DAMAGE, 0);
            var item = new JsonObject();
            item.addProperty("slot", slot.name().toLowerCase(java.util.Locale.ROOT));
            item.addProperty("item", data.name());
            item.addProperty("left", max - damage);
            item.addProperty("max", max);
            worn.add(item);
        }
        json.add("durability", worn);
    }

    private static String name(ItemStack stack) {
        var data = ItemRegistry.REGISTRY.get(stack.getId());
        return data != null ? data.name() : "unknown";
    }

    /** The block at a position, or null if that chunk isn't loaded. */
    static JsonObject block(int x, int y, int z) {
        var json = new JsonObject();
        boolean loaded = CACHE.getChunkCache().get(x >> 4, z >> 4) != null;
        json.addProperty("loaded", loaded);
        if (loaded) json.addProperty("block", World.getBlock(x, y, z).name());
        return json;
    }

    private static double distanceSq(Entity a, Entity b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
