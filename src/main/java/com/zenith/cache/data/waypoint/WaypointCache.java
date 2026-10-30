package com.zenith.cache.data.waypoint;

import com.viaversion.viaversion.util.Either;
import com.zenith.cache.CacheResetType;
import com.zenith.cache.CachedData;
import lombok.Data;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpSession;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.WaypointOperation;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.zenith.Globals.CACHE_LOG;

@Data
public class WaypointCache implements CachedData {
    private final Map<Either<UUID, String>, TrackedWaypoint> waypoints = new ConcurrentHashMap<>();

    @Override
    public void getPackets(@NonNull final Consumer<Packet> consumer, final @NonNull TcpSession session) {
        for (var waypoint : waypoints.values()) {
            consumer.accept(new ClientboundTrackedWaypointPacket(WaypointOperation.TRACK, waypoint));
        }
    }

    @Override
    public void reset(final CacheResetType type) {
        if (type == CacheResetType.RESPAWN) return;
        waypoints.clear();
    }

    public void updateWaypoints(ClientboundTrackedWaypointPacket packet) {
        switch (packet.getOperation()) {
            case TRACK, UPDATE -> {
                TrackedWaypoint waypoint = packet.getWaypoint();
                Either<UUID, String> key;
                if (waypoint.uuid() != null) {
                    key = Either.left(waypoint.uuid());
                } else if (waypoint.id() != null) {
                    key = Either.right(waypoint.id());
                } else {
                    CACHE_LOG.error("Invalid waypoint received, no UUID or ID present: {}", waypoint);
                    return; // Invalid waypoint, skip
                }
                waypoints.put(key, waypoint);
            }
            case UNTRACK -> {
                Either<UUID, String> key;
                if (packet.getWaypoint().uuid() != null) {
                    key = Either.left(packet.getWaypoint().uuid());
                } else if (packet.getWaypoint().id() != null) {
                    key = Either.right(packet.getWaypoint().id());
                } else {
                    CACHE_LOG.error("Invalid waypoint untrack request, no UUID or ID present: {}", packet.getWaypoint());
                    return; // Invalid waypoint, skip
                }
                waypoints.remove(key);
            }
        }
    }
}
