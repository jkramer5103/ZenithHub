package com.zenith.control;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Renders text the way an unmodded en_us client with default keybinds would (Component.getString()). Servers probe
 * clients with translation keys and keybinds in signs: a client with mods, another language or custom keybinds
 * sends a different text back.
 */
public final class VanillaText {
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
    private static final Map<String, String> DEFAULT_KEYS = Map.ofEntries(
        Map.entry("key.attack", "key.mouse.left"),
        Map.entry("key.use", "key.mouse.right"),
        Map.entry("key.pickItem", "key.mouse.middle"),
        Map.entry("key.forward", "key.keyboard.w"),
        Map.entry("key.left", "key.keyboard.a"),
        Map.entry("key.back", "key.keyboard.s"),
        Map.entry("key.right", "key.keyboard.d"),
        Map.entry("key.jump", "key.keyboard.space"),
        Map.entry("key.sneak", "key.keyboard.left.shift"),
        Map.entry("key.sprint", "key.keyboard.left.control"),
        Map.entry("key.drop", "key.keyboard.q"),
        Map.entry("key.inventory", "key.keyboard.e"),
        Map.entry("key.chat", "key.keyboard.t"),
        Map.entry("key.playerlist", "key.keyboard.tab"),
        Map.entry("key.command", "key.keyboard.slash"),
        Map.entry("key.socialInteractions", "key.keyboard.p"),
        Map.entry("key.screenshot", "key.keyboard.f2"),
        Map.entry("key.togglePerspective", "key.keyboard.f5"),
        Map.entry("key.smoothCamera", "key.keyboard.unknown"),
        Map.entry("key.fullscreen", "key.keyboard.f11"),
        Map.entry("key.spectatorOutlines", "key.keyboard.unknown"),
        Map.entry("key.swapOffhand", "key.keyboard.f"),
        Map.entry("key.saveToolbarActivator", "key.keyboard.c"),
        Map.entry("key.loadToolbarActivator", "key.keyboard.x"),
        Map.entry("key.advancements", "key.keyboard.l"),
        Map.entry("key.hotbar.1", "key.keyboard.1"),
        Map.entry("key.hotbar.2", "key.keyboard.2"),
        Map.entry("key.hotbar.3", "key.keyboard.3"),
        Map.entry("key.hotbar.4", "key.keyboard.4"),
        Map.entry("key.hotbar.5", "key.keyboard.5"),
        Map.entry("key.hotbar.6", "key.keyboard.6"),
        Map.entry("key.hotbar.7", "key.keyboard.7"),
        Map.entry("key.hotbar.8", "key.keyboard.8"),
        Map.entry("key.hotbar.9", "key.keyboard.9")
    );
    // printable keys show the character GLFW names them by, not their translation
    private static final Map<String, String> PRINTABLE_KEYS = Map.of("key.keyboard.slash", "/");
    private static volatile Map<String, String> language;

    private VanillaText() {}

    public static boolean hasProbe(final Component component) {
        if (component instanceof TranslatableComponent || component instanceof KeybindComponent) return true;
        for (var child : component.children()) if (hasProbe(child)) return true;
        return false;
    }

    public static String getString(final Component component) {
        var out = new StringBuilder();
        append(component, out, 0);
        return out.toString();
    }

    private static void append(final Component component, final StringBuilder out, final int depth) {
        if (depth > 32) return;
        switch (component) {
            case TextComponent text -> out.append(text.content());
            case TranslatableComponent tr -> appendTranslatable(tr, out, depth);
            case KeybindComponent kb -> out.append(keyName(kb.keybind()));
            default -> {} // score, selector and nbt contents are resolved by the server or render empty
        }
        for (var child : component.children()) append(child, out, depth + 1);
    }

    private static void appendTranslatable(final TranslatableComponent tr, final StringBuilder out, final int depth) {
        var lang = language();
        var format = lang.get(tr.key());
        if (format == null) format = tr.fallback() != null ? tr.fallback() : tr.key();
        var args = tr.arguments();
        var result = new StringBuilder();
        var matcher = FORMAT.matcher(format);
        int last = 0, next = 0;
        try {
            while (matcher.find()) {
                result.append(format, last, matcher.start());
                last = matcher.end();
                var type = matcher.group(2);
                if ("%".equals(type) && matcher.group(1) == null) {
                    result.append('%');
                    continue;
                }
                if (!"s".equals(type)) throw new IllegalArgumentException();
                int index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) - 1 : next++;
                if (index < 0 || index >= args.size()) throw new IllegalArgumentException();
                append(args.get(index).asComponent(), result, depth + 1);
            }
            result.append(format, last, format.length());
        } catch (final IllegalArgumentException e) {
            // vanilla shows the raw format when it can't apply the arguments
            result.setLength(0);
            result.append(format);
        }
        out.append(result);
    }

    private static String keyName(final String keybind) {
        var lang = language();
        var key = DEFAULT_KEYS.get(keybind);
        if (key == null) return lang.getOrDefault(keybind, keybind);
        var printable = PRINTABLE_KEYS.get(key);
        if (printable != null) return printable;
        var name = key.substring(key.lastIndexOf('.') + 1);
        if (key.startsWith("key.keyboard.") && name.length() == 1) return name.toUpperCase(Locale.ROOT);
        return lang.getOrDefault(key, key);
    }

    private static Map<String, String> language() {
        var loaded = language;
        if (loaded != null) return loaded;
        synchronized (VanillaText.class) {
            if (language != null) return language;
            var map = new HashMap<String, String>();
            // vanilla's full en_us, unlike Zenith's language.smile, which only has some keys, in MessageFormat
            try (var in = new GZIPInputStream(VanillaText.class.getResourceAsStream("/mcdata/en_us.json.gz"))) {
                var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var entry : json.entrySet()) map.put(entry.getKey(), entry.getValue().getAsString());
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to load the vanilla language", e);
            }
            language = map;
            return map;
        }
    }
}
