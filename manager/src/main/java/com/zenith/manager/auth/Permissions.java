package com.zenith.manager.auth;

import com.zenith.manager.state.State;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one permission system shared by the web UI, the lobby and Discord.
 *
 * A person is identified by any of: a web user, a Minecraft name, a Discord ID.
 * Their capabilities on an instance are the union of every grant matching any of their identities.
 * Owners have every capability on their instances, admins everywhere.
 */
public final class Permissions {
    private Permissions() {}

    public record Identity(State.User user, Set<String> mcNames, String discordId) {
        public static Identity of(State.User user) {
            var names = new java.util.HashSet<String>();
            for (var n : user.mcNames) names.add(n.toLowerCase(Locale.ROOT));
            return new Identity(user, names, user.discordId);
        }

        public boolean admin() {
            return user != null && user.admin;
        }
    }

    public static String userSubject(String userId) {
        return "user:" + userId;
    }

    /** Keeps the name's casing for display; matching ignores case. */
    public static String mcSubject(String name) {
        return "mc:" + name;
    }

    public static boolean sameSubject(String a, String b) {
        if (a == null || b == null) return false;
        return a.startsWith("mc:") ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    public static String discordSubject(String id) {
        return "discord:" + id;
    }

    public static Set<Capability> capabilities(Identity who, State.Instance instance) {
        if (who.admin() || owns(who, instance)) return EnumSet.allOf(Capability.class);
        var caps = EnumSet.noneOf(Capability.class);
        for (var grant : instance.grants) {
            if (matches(who, grant.subject)) {
                if (grant.role != null) caps.addAll(grant.role.capabilities());
                if (grant.extra != null) caps.addAll(grant.extra);
            }
        }
        return caps;
    }

    /** Someone gave this person access, as opposed to admin rights or ownership. */
    public static boolean sharedWith(Identity who, State.Instance instance) {
        return instance.grants.stream().anyMatch(g -> matches(who, g.subject));
    }

    public static boolean owns(Identity who, State.Instance instance) {
        return who.user() != null && who.user().id.equals(instance.ownerId);
    }

    public static boolean has(Identity who, State.Instance instance, Capability cap) {
        return capabilities(who, instance).contains(cap);
    }

    /** A grant may only hand out capabilities the granter holds themselves. */
    public static boolean canGrant(Identity granter, State.Instance instance, Role role, Set<Capability> extra) {
        var own = capabilities(granter, instance);
        if (!own.contains(Capability.SHARE)) return false;
        var requested = role.capabilities();
        if (extra != null) requested.addAll(extra);
        return own.containsAll(requested);
    }

    private static boolean matches(Identity who, String subject) {
        if (subject == null) return false;
        if (who.user() != null && subject.equals(userSubject(who.user().id))) return true;
        if (subject.startsWith("mc:") && who.mcNames().contains(subject.substring(3).toLowerCase(Locale.ROOT))) return true;
        return who.discordId() != null && subject.equals(discordSubject(who.discordId()));
    }
}
