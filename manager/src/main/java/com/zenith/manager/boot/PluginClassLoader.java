package com.zenith.manager.boot;

import net.lenni0451.classtransform.TransformerManager;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.jar.Manifest;

/**
 * Loads the manager and the plugins' manager jars child-first, so plugin mixins can change any class outside the
 * JDK. Only this package, ClassTransform and Gson come from the parent. The bot has its own copy
 * ({@code com.zenith.control.boot.PluginClassLoader}).
 */
public final class PluginClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private volatile TransformerManager transformers;

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    void setTransformers(TransformerManager transformers) {
        this.transformers = transformers;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = ClassLoader.getPlatformClassLoader().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = fromParent(name) ? getParent().loadClass(name) : findClass(name);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private static boolean fromParent(String name) {
        return name.startsWith("com.zenith.manager.boot.")
            || name.startsWith("net.lenni0451.classtransform.")
            || name.startsWith("org.objectweb.asm.")
            // the boot package hands Gson objects (manifest defaults) to the manager
            || name.startsWith("com.google.gson.");
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        var transformers = this.transformers;
        if (transformers == null) return super.findClass(name);
        var resource = findResource(name.replace('.', '/') + ".class");
        if (resource == null) throw new ClassNotFoundException(name);
        try {
            var connection = resource.openConnection();
            byte[] original;
            try (var in = connection.getInputStream()) {
                original = in.readAllBytes();
            }
            byte[] transformed;
            try {
                transformed = transformers.transform(name, original);
            } catch (Throwable failure) {
                BootInfo.error(null, "Couldn't change " + name + ": " + failure);
                return super.findClass(name);
            }
            if (transformed == null) return super.findClass(name);
            var source = resource;
            Manifest manifest = null;
            Certificate[] certificates = null;
            if (connection instanceof JarURLConnection jar) {
                source = jar.getJarFileURL();
                manifest = jar.getManifest();
                certificates = jar.getCertificates();
            }
            var dot = name.lastIndexOf('.');
            if (dot > 0 && getDefinedPackage(name.substring(0, dot)) == null) {
                try {
                    if (manifest == null) definePackage(name.substring(0, dot), null, null, null, null, null, null, null);
                    else definePackage(name.substring(0, dot), manifest, source);
                } catch (IllegalArgumentException ignored) {
                    // defined by another thread in the meantime
                }
            }
            return defineClass(name, transformed, 0, transformed.length, new CodeSource(source, certificates));
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        } catch (LinkageError failure) {
            BootInfo.error(null, "Couldn't load the changed " + name + ": " + failure);
            return super.findClass(name);
        }
    }

    @Override
    public URL getResource(String name) {
        var own = findResource(name);
        return own != null ? own : ClassLoader.getPlatformClassLoader().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        var resources = new LinkedHashSet<URL>();
        resources.addAll(Collections.list(findResources(name)));
        resources.addAll(Collections.list(ClassLoader.getPlatformClassLoader().getResources(name)));
        return Collections.enumeration(resources);
    }
}
