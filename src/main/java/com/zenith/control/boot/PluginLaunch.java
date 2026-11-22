package com.zenith.control.boot;

import com.google.gson.JsonParser;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.mixinstranslator.MixinsTranslator;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

/**
 * Entry point of the ZenithProxy jar. Without plugins it simply starts ZenithProxy.
 * With {@code ZENITH_PLUGINS_FILE} (written by the ZenithHub manager) it puts the plugins' jars next to ZenithProxy
 * in a {@link PluginClassLoader}, registers their mixins, and starts ZenithProxy inside it. The native image
 * starts {@code com.zenith.Proxy} directly and never runs plugins; the manager uses the jar for instances that need them.
 */
public final class PluginLaunch {
    public static final String FILE_ENV = "ZENITH_PLUGINS_FILE";

    private PluginLaunch() {}

    public static void main(String[] args) throws Throwable {
        var file = System.getenv(FILE_ENV);
        if (file == null || file.isBlank() || !Files.exists(Path.of(file))) {
            invokeMain(PluginLaunch.class.getClassLoader(), args);
            return;
        }
        var plugins = JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject().getAsJsonArray("plugins");
        var urls = new LinkedHashSet<URL>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toAbsolutePath().toUri().toURL());
        }
        for (var plugin : plugins) {
            for (var jar : plugin.getAsJsonObject().getAsJsonArray("jars")) {
                urls.add(Path.of(jar.getAsString()).toUri().toURL());
            }
        }
        var loader = new PluginClassLoader(urls.toArray(URL[]::new), PluginLaunch.class.getClassLoader());
        var transformers = new TransformerManager(new BasicClassProvider(loader));
        transformers.setFailStrategy(FailStrategy.THROW);
        transformers.addTransformerPreprocessor(new MixinsTranslator());
        boolean any = false;
        for (var element : plugins) {
            var plugin = element.getAsJsonObject();
            var id = plugin.get("id").getAsString();
            if (!plugin.has("mixins")) continue;
            for (var mixin : plugin.getAsJsonArray("mixins")) {
                try {
                    transformers.addTransformer(mixin.getAsString());
                    any = true;
                } catch (Throwable e) {
                    PluginClassLoader.ERRORS.add(id + ": couldn't register " + mixin.getAsString() + ": " + e);
                }
            }
        }
        if (any) loader.setTransformers(transformers);
        Thread.currentThread().setContextClassLoader(loader);
        loader.loadClass("com.zenith.control.plugins.BotPlugins").getMethod("install", String.class).invoke(null, file);
        invokeMain(loader, args);
    }

    private static void invokeMain(ClassLoader loader, String[] args) throws Throwable {
        try {
            loader.loadClass("com.zenith.Proxy").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
