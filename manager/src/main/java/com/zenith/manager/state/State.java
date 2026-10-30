package com.zenith.manager.state;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Role;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the manager persists, serialized to {@code /data/manager/state.json}.
 * Only mutate through {@link StateStore#update}.
 */
public final class State {
    public int version = 2;
    public List<User> users = new ArrayList<>();
    public List<Account> accounts = new ArrayList<>();
    public List<ServerProfile> servers = new ArrayList<>();
    public List<Instance> instances = new ArrayList<>();
    /** Global settings layer, applied on top of Zenith's defaults for every instance. */
    public JsonObject globalSettings = new JsonObject();
    public ManagerSettings settings = new ManagerSettings();
    public ProxyPoolSettings proxyPool = new ProxyPoolSettings();

    /** A list of proxies from a provider's download URL (a secret file); each account gets one of its own. */
    public static final class ProxyPoolSettings {
        public boolean enabled = false;
        /** SOCKS5, SOCKS4 or HTTP */
        public String type = "SOCKS5";
        /** account id to the proxy's "host:port"; kept while the proxy stays in the list */
        public java.util.Map<String, String> assignments = new java.util.LinkedHashMap<>();
        public long refreshedAt;
        /** the last refresh's failure, cleared on success */
        public String error;
    }

    public static final class ManagerSettings {
        /** Address players use to reach the lobby; shown in the UI and passed to instances as proxyIP. */
        public String publicAddress = "localhost";
        /**
         * Lets users set their own outbound proxy. Off, every instance uses the global proxy (the global settings
         * layer's connection proxy) or none. On, they can change it, but not turn it off while the global one is on.
         */
        @SerializedName(value = "userProxiesAllowed", alternate = "outboundProxiesEnabled")
        public boolean userProxiesAllowed = false;
        /** Runs the central Discord bot; its token is kept in a separate secret file. */
        public boolean discordEnabled = false;
    }

    public static final class User {
        public String id;
        public String username;
        public String passwordHash;
        public boolean admin;
        public List<String> mcNames = new ArrayList<>();
        public String discordId;
        public long createdAt;
    }

    public enum AccountType { MSA, OFFLINE }

    public static final class Account {
        public String id;
        public AccountType type;
        public String username;
        public String uuid;
        public long addedAt;
        /** The user who added the account; they own it and all its instances. */
        public String ownerId;
        /** Last background refresh failure; cleared on success. */
        public String authError;
        /** When a login without a refresh token (a pasted access token) stops working; 0 when it renews itself. */
        public long tokenExpiresAt;
        /** The account's category in the Discord server. */
        public String discordCategoryId;
    }

    public static final class ServerProfile {
        public String id;
        public String name;
        public String address;
        public int port = 25565;
        /** The user who added it, or null for servers the admin offers to everyone. */
        public String ownerId;
        public JsonObject settings = new JsonObject();
    }

    public enum Autostart { RESTORE, ALWAYS, NEVER }

    public static final class Instance {
        public String id;
        public String name;
        public String accountId;
        public String serverId;
        /** The owner of the account; has every capability. */
        public String ownerId;
        public JsonObject settings = new JsonObject();
        public Autostart autostart = Autostart.RESTORE;
        /** Whether the instance should be running; restored on manager start. */
        public boolean desiredRunning;
        /** Whether the instance was connected to its server when last observed; restored on start. */
        public boolean lastConnected;
        public int mcPort;
        public int controlPort;
        /** Copy-on-write: permission checks and log routing read it without the store's lock. */
        public List<Grant> grants = new java.util.concurrent.CopyOnWriteArrayList<>();
        public long createdAt;
        /** The instance's channel in the Discord server. */
        public String discordChannelId;
    }

    /**
     * Access to one instance. {@code subject} is {@code user:<id>}, {@code mc:<lowercase name>} or {@code discord:<id>}.
     */
    public static final class Grant {
        public String subject;
        public Role role;
        public Set<Capability> extra = EnumSet.noneOf(Capability.class);
        public String grantedBy;
    }
}
