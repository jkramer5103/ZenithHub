package com.zenith.manager.boot;

import com.zenith.manager.ManagerMain;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.mixinstranslator.MixinsTranslator;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * The manager's entry point. Plugins with a manager side are loaded here, before anything else, so their mixins
 * can change any class: the manager then runs inside a {@link PluginClassLoader} with their jars. Without such
 * plugins it starts the manager directly.
 */
public final class Boot {
    private Boot() {}

    public static void main(String[] args) throws Throwable {
        var dataDir = Path.of(System.getenv().getOrDefault("ZENITH_DATA_DIR", "/data")).toAbsolutePath();
        record Candidate(PluginRegistry.Entry entry, PluginManifest manifest, Path dir) {}
        var candidates = new ArrayList<Candidate>();
        try {
            for (var entry : PluginRegistry.read(dataDir)) {
                if (!entry.enabled) continue;
                var dir = PluginRegistry.codeDir(dataDir, entry);
                try {
                    var manifest = PluginManifest.read(dir);
                    if (manifest.manager().present()) candidates.add(new Candidate(entry, manifest, dir));
                } catch (Exception e) {
                    BootInfo.error(entry.id, "Couldn't read the plugin: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            BootInfo.error(null, "Couldn't read the plugin list: " + e);
        }
        if (candidates.isEmpty()) {
            ManagerMain.main(args);
            return;
        }
        var safe = System.getenv("ZENITH_SAFE_MODE");
        if (safe != null && !safe.isBlank() && !safe.equals("0") && !safe.equalsIgnoreCase("false")) {
            BootInfo.safeMode = "ZENITH_SAFE_MODE is set, so plugins didn't load into the manager.";
        } else if (BootInfo.failedStarts(dataDir) >= 2) {
            BootInfo.safeMode = "The last two starts with plugins didn't finish, so this one ran without them.";
            BootInfo.setFailedStarts(dataDir, 0);
        }
        if (BootInfo.safeMode != null) {
            System.err.println("ZenithHub: " + BootInfo.safeMode);
            ManagerMain.main(args);
            return;
        }
        BootInfo.setFailedStarts(dataDir, BootInfo.failedStarts(dataDir) + 1);

        var urls = new LinkedHashSet<URL>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toAbsolutePath().toUri().toURL());
        }
        for (var c : candidates) {
            for (var jar : c.manifest.manager().jars()) urls.add(c.dir.resolve(jar).toUri().toURL());
        }
        var loader = new PluginClassLoader(urls.toArray(URL[]::new), Boot.class.getClassLoader());
        var transformers = new TransformerManager(new BasicClassProvider(loader));
        transformers.setFailStrategy(FailStrategy.THROW);
        transformers.addTransformerPreprocessor(new MixinsTranslator());
        boolean any = false;
        for (var c : candidates) {
            BootInfo.LOADED.put(c.entry.id, c.entry.revision);
            BootInfo.MANIFESTS.put(c.entry.id, c.manifest);
            for (var mixin : c.manifest.manager().mixins()) {
                try {
                    transformers.addTransformer(mixin);
                    any = true;
                } catch (Throwable e) {
                    BootInfo.error(c.entry.id, "Couldn't register the mixin " + mixin + ": " + e);
                }
            }
        }
        if (any) loader.setTransformers(transformers);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            loader.loadClass("com.zenith.manager.ManagerMain").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
