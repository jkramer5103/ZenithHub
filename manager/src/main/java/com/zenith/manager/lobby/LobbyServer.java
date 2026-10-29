package com.zenith.manager.lobby;

import com.zenith.manager.auth.AccessService;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Permissions;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.instances.InstanceRuntime;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpConnectionManager;
import org.geysermc.mcprotocollib.network.tcp.TcpServer;
import org.geysermc.mcprotocollib.network.tcp.TcpServerSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-game lobby: an empty void with a chest menu of the instances a player may join.
 * Picking one stores where to go in a cookie and transfers the client back to the router, which pipes it to the instance.
 */
public final class LobbyServer {
    private static final Logger LOG = LoggerFactory.getLogger("Lobby");

    // 1.21.4 item ids
    private static final int PLAYER_HEAD = 1156;
    private static final int COMPASS = 961;
    private static final int GRAY_PANE = 517;
    private static final int NAME_TAG = 1181;

    private static final int MENU_ID = 1;
    private static final ContainerType[] MENU_TYPES = {
        ContainerType.GENERIC_9X1, ContainerType.GENERIC_9X2, ContainerType.GENERIC_9X3,
        ContainerType.GENERIC_9X4, ContainerType.GENERIC_9X5, ContainerType.GENERIC_9X6};
    private static final Key TARGET = Key.key("zenith", "target");
    private static final Key TRANSFER_SRC = Key.key("zenith", "zenith-transfer-src");
    private static final Key SPECTATOR = Key.key("zenith", "zenith-spectator");

    private final StateStore store;
    private final AccessService access;
    private final Supervisor supervisor;
    private final PlayerDirectory players;
    private final Map<String, Visitor> visitors = new ConcurrentHashMap<>();
    private TcpServer server;

    public LobbyServer(StateStore store, AccessService access, Supervisor supervisor, PlayerDirectory players) {
        this.store = store;
        this.access = access;
        this.supervisor = supervisor;
        this.players = players;
        access.onMcLinked((mcName, username) -> {
            var visitor = visitors.get(mcName.toLowerCase(Locale.ROOT));
            if (visitor == null) return;
            visitor.chat(Component.text("Linked to " + username + " on the web page.", NamedTextColor.GREEN));
            visitor.showMenu(true);
        });
    }

    /**
     * The default registries reference enchantment tags the lobby never sends. Older clients ignore that,
     * 26.x clients refuse the whole registry ("Network Protocol Error"). Nothing in the lobby is enchanted.
     */
    private static final SessionAdapter REGISTRY_FIX = new SessionAdapter() {
        @Override
        public Packet packetSending(Session session, Packet packet) {
            if (packet instanceof ClientboundRegistryDataPacket registry && registry.getRegistry().endsWith("enchantment")) {
                return registry.withEntries(List.of());
            }
            return packet;
        }
    };

    private volatile boolean onlineMode;

    /** Binds to a free local port; only the router connects to it. */
    public int start(boolean onlineMode, Path viaDir) {
        this.onlineMode = onlineMode;
        LobbyVia.init(viaDir);
        var connections = new TcpConnectionManager();
        server = new TcpServer("127.0.0.1", 0, () -> {
            var protocol = new MinecraftProtocol();
            protocol.setUseDefaultListeners(true);
            return protocol;
        }, connections, address -> {
            var session = new TcpServerSession(address.getHostString(), address.getPort(), server.createPacketProtocol(), server);
            session.addListener(REGISTRY_FIX);
            return session;
        });
        server.setGlobalFlag(MinecraftConstants.SERVER_CHANNEL_INITIALIZER, LobbyVia.CHANNELS);
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, onlineMode);
        if (!onlineMode) LOG.warn("Lobby is in offline mode: anyone can claim any name");
        server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, -1);
        server.setGlobalFlag(MinecraftConstants.SERVER_INFO_BUILDER_KEY, session -> new ServerStatusInfo(
            Component.text("ZenithHub", NamedTextColor.GREEN),
            new PlayerInfo(0, 0, List.of()),
            new VersionInfo("1.20.5+", Router.PROTOCOL),
            null,
            false));
        server.setGlobalFlag(MinecraftConstants.SERVER_LOGIN_HANDLER_KEY, this::loggedIn);
        server.bind(true);
        LOG.info("Lobby ready on local port {}", server.getPort());
        return server.getPort();
    }

    public void stop() {
        if (server != null) server.close(true);
    }

    // --- per player ---

    private record Entry(String instanceId, String name, Set<Capability> caps, boolean mine, String ownerName) {}

    private final class Visitor extends SessionAdapter {
        final Session session;
        final GameProfile profile;
        List<Entry> entries = List.of();
        /** Menu slot of the "link this account" item, or -1. */
        int linkSlot = -1;
        int stateId;
        volatile boolean busy;

        Visitor(Session session, GameProfile profile) {
            this.session = session;
            this.profile = profile;
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            switch (packet) {
                case ServerboundContainerClickPacket click when click.getContainerId() == MENU_ID -> onClick(click);
                case ServerboundContainerClickPacket click -> resendInventory();
                case ServerboundUseItemPacket use -> openMenu();
                default -> {}
            }
        }

        @Override
        public void disconnected(Session session, Component reason, Throwable cause) {
            visitors.remove(profile.getName().toLowerCase(Locale.ROOT), this);
        }

        void openMenu() {
            showMenu(true);
        }

        /** Sends the menu; without reopening it, just puts back what the client predicted moving. */
        void showMenu(boolean open) {
            entries = visibleInstances(profile.getName());
            if (entries.isEmpty()) {
                session.disconnect("You are not whitelisted on this server.");
                return;
            }
            boolean offerLink = access.linkedUser(profile.getName()).isEmpty();
            int slots = entries.size() + (offerLink ? 1 : 0);
            int rows = Math.min(6, Math.max(1, (slots + 8) / 9));
            var type = MENU_TYPES[rows - 1];
            var items = new ItemStack[rows * 9 + 36];
            for (int i = 0; i < rows * 9; i++) items[i] = i < entries.size() ? head(entries.get(i)) : pane();
            linkSlot = offerLink ? rows * 9 - 1 : -1;
            if (offerLink) items[linkSlot] = linkTag();
            // the player's own hotbar is shown below the menu
            items[rows * 9 + 27] = compass();
            if (open) session.send(new ClientboundOpenScreenPacket(MENU_ID, type, Component.text("Choose an instance")));
            session.send(new ClientboundContainerSetContentPacket(MENU_ID, ++stateId, items, null));
        }

        void resendInventory() {
            var items = new ItemStack[46];
            items[36] = compass();
            session.send(new ClientboundContainerSetContentPacket(0, ++stateId, items, null));
        }

        void onClick(ServerboundContainerClickPacket click) {
            int slot = click.getSlot();
            // the client predicted picking the item up; put everything back
            showMenu(false);
            if (slot >= 0 && slot == linkSlot) {
                showLinkCode();
                return;
            }
            if (slot < 0 || slot >= entries.size() || busy) return;
            var entry = entries.get(slot);
            boolean right = click.getActionParam() == ClickItemAction.RIGHT_CLICK;
            boolean canPlay = entry.caps().contains(Capability.PLAY);
            boolean canSpectate = entry.caps().contains(Capability.SPECTATE);
            boolean spectate = right ? canSpectate : !canPlay;
            if (spectate && !canSpectate) return;
            if (!spectate && !canPlay) return;
            join(entry, spectate);
        }

        void join(Entry entry, boolean spectate) {
            if (supervisor.isConnected(entry.instanceId())) {
                transfer(entry, spectate);
                return;
            }
            if (!entry.caps().contains(Capability.CONTROL)) {
                chat(Component.text(entry.name() + " is offline.", NamedTextColor.RED));
                return;
            }
            busy = true;
            chat(Component.text("Connecting " + entry.name() + "…", NamedTextColor.GRAY));
            Thread.ofVirtual().name("lobby-connect").start(() -> {
                try {
                    long since = System.currentTimeMillis();
                    supervisor.connect(entry.instanceId(), profile.getName() + " (lobby)");
                    var failure = supervisor.awaitConnected(entry.instanceId(), since, Duration.ofMinutes(2));
                    if (!session.isConnected()) return;
                    if (failure == null) transfer(entry, spectate);
                    else chat(Component.text(entry.name() + " couldn't connect: " + failure, NamedTextColor.RED));
                } catch (Exception e) {
                    chat(Component.text(entry.name() + " couldn't connect: " + e.getMessage(), NamedTextColor.RED));
                } finally {
                    busy = false;
                }
            });
        }

        void transfer(Entry entry, boolean spectate) {
            var hostPort = ConfigComposer.publicHostPort(store.read(s -> s.settings));
            int colon = hostPort.lastIndexOf(':');
            var host = hostPort.substring(0, colon).replace("[", "").replace("]", "");
            int port = Integer.parseInt(hostPort.substring(colon + 1));
            session.send(new ClientboundContainerClosePacket(MENU_ID));
            session.send(new ClientboundStoreCookiePacket(TARGET, entry.instanceId().getBytes(StandardCharsets.UTF_8)));
            session.send(new ClientboundStoreCookiePacket(TRANSFER_SRC, "lobby".getBytes(StandardCharsets.UTF_8)));
            session.send(new ClientboundStoreCookiePacket(SPECTATOR, String.valueOf(spectate).getBytes(StandardCharsets.UTF_8)));
            session.send(new ClientboundTransferPacket(host, port));
            LOG.info("{} joins {} as {}", profile.getName(), entry.name(), spectate ? "spectator" : "player");
        }

        void showLinkCode() {
            var code = access.mcLinkCode(profile.getName());
            session.send(new ClientboundContainerClosePacket(MENU_ID));
            chat(Component.text()
                .append(Component.text("Your link code: ", NamedTextColor.GRAY))
                .append(Component.text(code, NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("On the web page, open your user menu → Minecraft accounts and enter it. "
                    + "Linked accounts get full access to everything you own.", NamedTextColor.GRAY))
                .build());
        }

        void chat(Component message) {
            session.send(new ClientboundSystemChatPacket(message, false));
        }
    }

    private void loggedIn(Session session) {
        GameProfile profile = session.getFlag(MinecraftConstants.PROFILE_KEY);
        if (profile == null) {
            session.disconnect("Login failed");
            return;
        }
        // offline-mode UUIDs are made up from the name; instances check the real one, so look that up instead
        if (onlineMode) players.remember(profile.getName(), profile.getIdAsString());
        var visitor = new Visitor(session, profile);
        if (visibleInstances(profile.getName()).isEmpty()) {
            LOG.info("{} has no access to any instance", profile.getName());
            session.disconnect(noAccessMessage(profile.getName()));
            return;
        }
        var previous = visitors.put(profile.getName().toLowerCase(Locale.ROOT), visitor);
        if (previous != null && previous.session != session) previous.session.disconnect("You logged in from another location.");
        session.addListener(visitor);
        session.send(new ClientboundLoginPacket(
            1, false, new Key[]{Key.key("minecraft:overworld")}, 1, 2, 2, false, true, false,
            new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0, GameMode.ADVENTURE, GameMode.ADVENTURE, false, true, null, 0, 63),
            false));
        session.send(new ClientboundPlayerAbilitiesPacket(true, true, true, false, 0.05f, 0.1f));
        // above build height: the client leaves the loading screen without any chunks
        session.send(new ClientboundPlayerPositionPacket(1, 0.5, 1000, 0.5, 0, 0, 0, 0, 0));
        session.send(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
        session.send(new ClientboundSetHeldSlotPacket(0));
        visitor.resendInventory();
        visitor.chat(Component.text("Pick an instance. Use the compass to reopen the menu.", NamedTextColor.GRAY));
        visitor.openMenu();
    }

    private Component noAccessMessage(String mcName) {
        var linked = access.linkedUser(mcName);
        if (linked.isPresent()) {
            return Component.text()
                .append(Component.text("You don't have any instances yet.", NamedTextColor.RED))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("This account is linked to " + linked.get() + ". Add an account on the web page, "
                    + "or ask a friend to share theirs.", NamedTextColor.GRAY))
                .build();
        }
        return Component.text()
            .append(Component.text("You are not whitelisted on this server.", NamedTextColor.RED))
            .append(Component.newline()).append(Component.newline())
            .append(Component.text("Have a ZenithHub login? Link this account with code", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text(access.mcLinkCode(mcName), NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("under your user menu → Minecraft accounts on the web page.", NamedTextColor.GRAY))
            .build();
    }

    /** Instances this player may play or spectate, by name; their own come first. */
    private List<Entry> visibleInstances(String mcName) {
        Permissions.Identity who = access.identityOfMcName(mcName);
        return store.read(s -> {
            var result = new ArrayList<Entry>();
            for (var instance : s.instances) {
                var caps = Permissions.capabilities(who, instance);
                if (caps.contains(Capability.PLAY) || caps.contains(Capability.SPECTATE)) {
                    var owner = s.users.stream().filter(u -> u.id.equals(instance.ownerId)).findFirst().map(u -> u.username).orElse(null);
                    result.add(new Entry(instance.id, instance.name, caps, Permissions.owns(who, instance), owner));
                }
            }
            result.sort(Comparator.comparing((Entry e) -> !e.mine()));
            return result;
        });
    }

    // --- items ---

    private ItemStack head(Entry entry) {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(entry.name(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        var lore = new ArrayList<Component>();
        var info = store.read(s -> {
            var instance = s.instances.stream().filter(i -> i.id.equals(entry.instanceId())).findFirst().orElse(null);
            if (instance == null) return null;
            var account = s.accounts.stream().filter(a -> a.id.equals(instance.accountId)).findFirst().orElse(null);
            var server = s.servers.stream().filter(x -> x.id.equals(instance.serverId)).findFirst().orElse(null);
            return new Object[]{account, server};
        });
        State.Account account = info == null ? null : (State.Account) info[0];
        State.ServerProfile server = info == null ? null : (State.ServerProfile) info[1];
        if (account != null) {
            lore.add(line(account.username + (server != null ? " on " + server.name : ""), NamedTextColor.GRAY));
            if (account.uuid != null) {
                try {
                    components.put(DataComponentTypes.PROFILE, new GameProfile(UUID.fromString(account.uuid), account.username));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        lore.add(entry.mine()
            ? line("Your account", NamedTextColor.GREEN)
            : line((entry.ownerName() != null ? entry.ownerName() + "'s" : "Someone else's") + " account", NamedTextColor.AQUA));
        lore.add(statusLine(entry.instanceId()));
        lore.add(Component.empty());
        if (entry.caps().contains(Capability.PLAY)) lore.add(line("Left-click: play", NamedTextColor.YELLOW));
        if (entry.caps().contains(Capability.SPECTATE)) lore.add(line("Right-click: spectate", NamedTextColor.YELLOW));
        components.put(DataComponentTypes.LORE, lore);
        return new ItemStack(PLAYER_HEAD, 1, components);
    }

    private Component statusLine(String instanceId) {
        var rt = supervisor.runtime(instanceId);
        if (rt.phase() != InstanceRuntime.Phase.RUNNING) {
            return switch (rt.phase()) {
                case STARTING -> line("Connecting", NamedTextColor.GOLD);
                case BACKOFF -> line("Crashed, restarting", NamedTextColor.RED);
                default -> line("Offline", NamedTextColor.DARK_GRAY);
            };
        }
        var status = rt.status();
        if (status == null || !status.has("connected") || !status.get("connected").getAsBoolean()) return line("Offline", NamedTextColor.DARK_GRAY);
        if (status.has("inQueue") && status.get("inQueue").getAsBoolean()) {
            return line("In queue: " + status.get("queuePosition").getAsInt(), NamedTextColor.GOLD);
        }
        return line("Online", NamedTextColor.GREEN);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack pane() {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(" "));
        return new ItemStack(GRAY_PANE, 1, components);
    }

    private static ItemStack linkTag() {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, line("Link this account", NamedTextColor.AQUA));
        components.put(DataComponentTypes.LORE, List.of(
            line("Connect this Minecraft account to your", NamedTextColor.GRAY),
            line("ZenithHub login to get full access", NamedTextColor.GRAY),
            line("to everything you own.", NamedTextColor.GRAY),
            Component.empty(),
            line("Click: show a link code", NamedTextColor.YELLOW)));
        return new ItemStack(NAME_TAG, 1, components);
    }

    private static ItemStack compass() {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, line("Choose an instance", NamedTextColor.GREEN));
        components.put(DataComponentTypes.LORE, List.of(line("Right-click to open", NamedTextColor.GRAY)));
        return new ItemStack(COMPASS, 1, components);
    }
}
