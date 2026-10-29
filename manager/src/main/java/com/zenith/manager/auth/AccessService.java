package com.zenith.manager.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.web.ApiException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Users and per-instance grants. Shared by the web UI, the lobby and the Discord bot,
 * so every surface enforces the same rules.
 */
public final class AccessService {
    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");
    public static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern DISCORD_ID = Pattern.compile("^[0-9]{15,21}$");

    private static final long LINK_CODE_LIFETIME_MS = 10 * 60_000;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final StateStore store;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    /** Minecraft link codes shown in-game: code → the player who saw it. */
    private final java.util.Map<String, McLinkCode> mcLinkCodes = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<java.util.function.BiConsumer<String, String>> linkListeners = new CopyOnWriteArrayList<>();
    /** Called with an instance id (or null for "possibly all") whenever who may access it changes. */
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public AccessService(StateStore store) {
        this.store = store;
    }

    public void onChange(Consumer<String> listener) {
        listeners.add(listener);
    }

    private void fire(String instanceId) {
        for (var l : listeners) {
            try {
                l.accept(instanceId);
            } catch (Exception ignored) {
                // a listener failing must not undo the change
            }
        }
    }

    // --- users ---

    public static void validateCredentials(String username, String password) {
        if (!USERNAME.matcher(username).matches()) throw ApiException.badRequest("Username must be 3-32 letters, digits, _ . or -");
        validatePassword(password);
    }

    public static void validatePassword(String password) {
        if (password.length() < 8) throw ApiException.badRequest("Password must be at least 8 characters");
    }

    public static List<String> normalizeMcNames(List<String> names) {
        var out = new ArrayList<String>();
        for (var raw : names) {
            var name = raw.trim();
            if (name.isEmpty()) continue;
            if (!MC_NAME.matcher(name).matches()) throw ApiException.badRequest("Invalid Minecraft name: " + name);
            if (out.stream().noneMatch(n -> n.equalsIgnoreCase(name))) out.add(name);
        }
        return out;
    }

    public JsonArray listUsers() {
        return store.read(s -> {
            var arr = new JsonArray();
            for (var u : s.users) arr.add(userJson(s, u));
            return arr;
        });
    }

    public String createUser(String username, String password, List<String> mcNames) {
        validateCredentials(username, password);
        var names = normalizeMcNames(mcNames);
        var id = store.updateAndGet(s -> {
            if (s.users.stream().anyMatch(u -> u.username.equalsIgnoreCase(username))) throw new IllegalStateException("That username is taken");
            checkMcNamesFree(s, names, null);
            var user = new State.User();
            user.id = UUID.randomUUID().toString();
            user.username = username;
            user.passwordHash = Passwords.hash(password);
            user.mcNames = names;
            user.createdAt = System.currentTimeMillis();
            s.users.add(user);
            return user.id;
        });
        fire(null);
        return id;
    }

    public void updateUser(String id, String password, List<String> mcNames, boolean unlinkDiscord) {
        if (password != null) validatePassword(password);
        var names = mcNames == null ? null : normalizeMcNames(mcNames);
        store.update(s -> {
            var user = findUser(s, id);
            if (password != null) user.passwordHash = Passwords.hash(password);
            if (names != null) {
                checkMcNamesFree(s, names, id);
                user.mcNames = names;
            }
            if (unlinkDiscord) user.discordId = null;
        });
        fire(null);
    }

    public void deleteUser(String id) {
        store.update(s -> {
            var user = findUser(s, id);
            if (user.admin) throw new IllegalStateException("The administrator can't be deleted");
            long accounts = s.accounts.stream().filter(a -> id.equals(a.ownerId)).count();
            if (accounts > 0) throw new IllegalStateException(user.username + " still has " + accounts + " account" + (accounts == 1 ? "" : "s") + ". Remove them first.");
            var servers = s.servers.stream().filter(x -> id.equals(x.ownerId)).map(x -> x.id).toList();
            if (s.instances.stream().anyMatch(i -> servers.contains(i.serverId))) throw new IllegalStateException("Instances still use " + user.username + "'s servers. Delete them first.");
            s.servers.removeIf(x -> id.equals(x.ownerId));
            s.users.remove(user);
            var subject = Permissions.userSubject(id);
            s.instances.forEach(i -> i.grants.removeIf(g -> subject.equals(g.subject)));
        });
        fire(null);
    }

    public void linkDiscord(String userId, String discordId) {
        store.update(s -> {
            for (var u : s.users) if (discordId.equals(u.discordId)) u.discordId = null;
            findUser(s, userId).discordId = discordId;
        });
        fire(null);
    }

    // --- linking Minecraft accounts ---

    private record McLinkCode(String name, long expiresAt) {}

    /** Called with (Minecraft name, web username) after a player linked their account. */
    public void onMcLinked(java.util.function.BiConsumer<String, String> listener) {
        linkListeners.add(listener);
    }

    /** The web user a Minecraft name is linked to, if any. */
    public java.util.Optional<String> linkedUser(String mcName) {
        return store.read(s -> s.users.stream().filter(u -> u.mcNames.stream().anyMatch(n -> n.equalsIgnoreCase(mcName))).map(u -> u.username).findFirst());
    }

    /**
     * A code proving that whoever enters it controls this Minecraft account: it's only shown in-game,
     * and the lobby only lets in players Mojang authenticated. The same code is reused while valid.
     */
    public String mcLinkCode(String mcName) {
        long now = System.currentTimeMillis();
        mcLinkCodes.values().removeIf(c -> c.expiresAt() < now);
        for (var e : mcLinkCodes.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(mcName)) return e.getKey();
        }
        var code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        mcLinkCodes.put(code.toString(), new McLinkCode(mcName, now + LINK_CODE_LIFETIME_MS));
        return code.toString();
    }

    /** Links the Minecraft account that saw this code to the user; returns its name. */
    public String claimMcLink(String userId, String rawCode) {
        var code = rawCode.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        var link = mcLinkCodes.get(code);
        if (link == null || link.expiresAt() < System.currentTimeMillis()) throw ApiException.badRequest("That code is wrong or expired. Join again for a new one.");
        var username = store.updateAndGet(s -> {
            var user = findUser(s, userId);
            for (var u : s.users) {
                if (u != user && u.mcNames.stream().anyMatch(n -> n.equalsIgnoreCase(link.name()))) {
                    throw new IllegalStateException(link.name() + " is already linked to " + u.username);
                }
            }
            user.mcNames.removeIf(n -> n.equalsIgnoreCase(link.name()));
            user.mcNames.add(link.name());
            return user.username;
        });
        mcLinkCodes.remove(code);
        fire(null);
        for (var l : linkListeners) {
            try {
                l.accept(link.name(), username);
            } catch (Exception ignored) {
            }
        }
        return link.name();
    }

    public void unlinkMc(String userId, String name) {
        store.update(s -> {
            if (!findUser(s, userId).mcNames.removeIf(n -> n.equalsIgnoreCase(name))) throw ApiException.notFound();
        });
        fire(null);
    }

    /** A Minecraft name identifies one person; two users claiming it would share each other's access. */
    private static void checkMcNamesFree(State s, List<String> names, String exceptUserId) {
        for (var u : s.users) {
            if (u.id.equals(exceptUserId)) continue;
            for (var n : names) {
                if (u.mcNames.stream().anyMatch(m -> m.equalsIgnoreCase(n))) {
                    throw new IllegalStateException(n + " already belongs to " + u.username);
                }
            }
        }
    }

    private static State.User findUser(State s, String id) {
        return s.users.stream().filter(u -> u.id.equals(id)).findFirst().orElseThrow(ApiException::notFound);
    }

    private static JsonObject userJson(State s, State.User u) {
        var json = new JsonObject();
        json.addProperty("id", u.id);
        json.addProperty("username", u.username);
        json.addProperty("admin", u.admin);
        var names = new JsonArray();
        u.mcNames.forEach(names::add);
        json.add("mcNames", names);
        json.addProperty("discordId", u.discordId);
        json.addProperty("createdAt", u.createdAt);
        var subject = Permissions.userSubject(u.id);
        json.addProperty("instanceCount", s.instances.stream().filter(i -> u.id.equals(i.ownerId) || i.grants.stream().anyMatch(g -> subject.equals(g.subject))).count());
        json.addProperty("accountCount", s.accounts.stream().filter(a -> u.id.equals(a.ownerId)).count());
        return json;
    }

    // --- grants ---

    /** Normalizes and validates a grant subject: user:&lt;id&gt;, mc:&lt;name&gt; or discord:&lt;id&gt;. */
    public String normalizeSubject(String subject) {
        if (subject == null) throw ApiException.badRequest("Missing subject");
        int colon = subject.indexOf(':');
        if (colon < 0) throw ApiException.badRequest("Invalid subject");
        var kind = subject.substring(0, colon);
        var value = subject.substring(colon + 1).trim();
        return switch (kind) {
            case "user" -> {
                if (store.read(s -> s.users.stream().noneMatch(u -> u.id.equals(value)))) throw ApiException.badRequest("Unknown user");
                yield Permissions.userSubject(value);
            }
            case "mc" -> {
                if (!MC_NAME.matcher(value).matches()) throw ApiException.badRequest("Invalid Minecraft name");
                yield Permissions.mcSubject(value);
            }
            case "discord" -> {
                if (!DISCORD_ID.matcher(value).matches()) throw ApiException.badRequest("Invalid Discord user ID");
                yield Permissions.discordSubject(value);
            }
            default -> throw ApiException.badRequest("Invalid subject");
        };
    }

    public JsonObject accessView(Permissions.Identity who, String instanceId) {
        return store.read(s -> {
            var instance = findInstance(s, instanceId);
            var json = new JsonObject();
            var grants = new JsonArray();
            for (var g : instance.grants) {
                var v = new JsonObject();
                v.addProperty("subject", g.subject);
                v.addProperty("label", label(s, g.subject));
                v.addProperty("role", g.role == null ? null : g.role.name());
                var extra = new JsonArray();
                if (g.extra != null) g.extra.forEach(c -> extra.add(c.name()));
                v.add("extra", extra);
                var caps = new JsonArray();
                grantCapabilities(g).forEach(c -> caps.add(c.name()));
                v.add("capabilities", caps);
                v.addProperty("grantedBy", g.grantedBy);
                v.addProperty("editable", Permissions.capabilities(who, instance).containsAll(grantCapabilities(g)));
                grants.add(v);
            }
            json.add("grants", grants);
            var own = new JsonArray();
            Permissions.capabilities(who, instance).forEach(c -> own.add(c.name()));
            json.add("myCapabilities", own);
            json.addProperty("owner", s.users.stream().filter(u -> u.id.equals(instance.ownerId)).map(u -> u.username).findFirst().orElse(null));
            // people you can pick by name
            var users = new JsonArray();
            for (var u : s.users) {
                if (u.admin || u.id.equals(instance.ownerId)) continue;
                var v = new JsonObject();
                v.addProperty("id", u.id);
                v.addProperty("username", u.username);
                users.add(v);
            }
            json.add("users", users);
            return json;
        });
    }

    public void putGrant(Permissions.Identity granter, String instanceId, String rawSubject, Role role, Set<Capability> extra, String grantedBy) {
        var subject = normalizeSubject(rawSubject);
        var extraCaps = EnumSet.noneOf(Capability.class);
        if (extra != null) extraCaps.addAll(extra);
        if (role == null) throw ApiException.badRequest("Missing role");
        store.update(s -> {
            var instance = findInstance(s, instanceId);
            if (!Permissions.canGrant(granter, instance, role, extraCaps)) throw ApiException.forbidden();
            if (granter.user() != null && subject.equals(Permissions.userSubject(granter.user().id)) && !granter.admin()) {
                throw ApiException.badRequest("You can't change your own access");
            }
            var existing = instance.grants.stream().filter(g -> Permissions.sameSubject(subject, g.subject)).findFirst().orElse(null);
            if (existing != null) {
                if (!Permissions.capabilities(granter, instance).containsAll(grantCapabilities(existing))) throw ApiException.forbidden();
                existing.subject = subject;
                existing.role = role;
                existing.extra = extraCaps;
                existing.grantedBy = grantedBy;
            } else {
                var g = new State.Grant();
                g.subject = subject;
                g.role = role;
                g.extra = extraCaps;
                g.grantedBy = grantedBy;
                instance.grants.add(g);
            }
            // capabilities already in the role are noise
            var grant = instance.grants.stream().filter(g -> Permissions.sameSubject(subject, g.subject)).findFirst().orElseThrow();
            grant.extra.removeAll(role.capabilities());
        });
        fire(instanceId);
    }

    public void removeGrant(Permissions.Identity granter, String instanceId, String rawSubject) {
        if (rawSubject == null) throw ApiException.badRequest("Missing subject");
        var subject = rawSubject;
        store.update(s -> {
            var instance = findInstance(s, instanceId);
            var own = Permissions.capabilities(granter, instance);
            if (!own.contains(Capability.SHARE)) throw ApiException.forbidden();
            var existing = instance.grants.stream().filter(g -> Permissions.sameSubject(subject, g.subject)).findFirst().orElseThrow(ApiException::notFound);
            if (!own.containsAll(grantCapabilities(existing))) throw ApiException.forbidden();
            instance.grants.remove(existing);
        });
        fire(instanceId);
    }

    public static Set<Capability> grantCapabilities(State.Grant g) {
        var caps = g.role == null ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(g.role.capabilities());
        if (g.extra != null) caps.addAll(g.extra);
        return caps;
    }

    private static String label(State s, String subject) {
        if (subject.startsWith("user:")) {
            var id = subject.substring(5);
            return s.users.stream().filter(u -> u.id.equals(id)).map(u -> u.username).findFirst().orElse("Deleted user");
        }
        if (subject.startsWith("discord:")) {
            var id = subject.substring(8);
            return s.users.stream().filter(u -> id.equals(u.discordId)).map(u -> u.username + " (Discord)").findFirst().orElse("Discord " + id);
        }
        return subject.substring(subject.indexOf(':') + 1);
    }

    // --- in-game access ---

    /** Minecraft names allowed to join an instance: controllers and spectator-only players. */
    public record JoinNames(List<String> players, List<String> spectators) {}

    public JoinNames joinNames(String instanceId) {
        return store.read(s -> {
            var instance = s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst().orElse(null);
            var players = new java.util.LinkedHashMap<String, String>();
            var spectators = new java.util.LinkedHashMap<String, String>();
            if (instance == null) return new JoinNames(List.of(), List.of());
            // admins and the owner may always play
            for (var u : s.users) {
                if (u.admin || u.id.equals(instance.ownerId)) u.mcNames.forEach(n -> players.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), n));
            }
            for (var g : instance.grants) {
                var caps = grantCapabilities(g);
                var target = caps.contains(Capability.PLAY) ? players : caps.contains(Capability.SPECTATE) ? spectators : null;
                if (target == null) continue;
                for (var name : mcNamesOf(s, g.subject)) target.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), name);
            }
            players.keySet().forEach(spectators::remove);
            return new JoinNames(List.copyOf(players.values()), List.copyOf(spectators.values()));
        });
    }

    /** Who a Minecraft player is: the web user owning that name (with their Discord link), or just the name. */
    public Permissions.Identity identityOfMcName(String name) {
        return store.read(s -> {
            for (var u : s.users) {
                if (u.mcNames.stream().anyMatch(n -> n.equalsIgnoreCase(name))) return Permissions.Identity.of(u);
            }
            return new Permissions.Identity(null, Set.of(name.toLowerCase(java.util.Locale.ROOT)), null);
        });
    }

    /** Who a Discord user is: the web user they linked, or just the Discord ID. */
    public Permissions.Identity identityOfDiscord(String discordId) {
        return store.read(s -> {
            for (var u : s.users) {
                if (discordId.equals(u.discordId)) return Permissions.Identity.of(u);
            }
            return new Permissions.Identity(null, Set.of(), discordId);
        });
    }

    private static List<String> mcNamesOf(State s, String subject) {
        if (subject.startsWith("mc:")) return List.of(subject.substring(3));
        if (subject.startsWith("user:")) {
            var id = subject.substring(5);
            return s.users.stream().filter(u -> u.id.equals(id)).findFirst().map(u -> u.mcNames).orElse(List.of());
        }
        if (subject.startsWith("discord:")) {
            var id = subject.substring(8);
            return s.users.stream().filter(u -> id.equals(u.discordId)).findFirst().map(u -> u.mcNames).orElse(List.of());
        }
        return List.of();
    }

    private static State.Instance findInstance(State s, String id) {
        return s.instances.stream().filter(i -> i.id.equals(id)).findFirst().orElseThrow(ApiException::notFound);
    }
}
