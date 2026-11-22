package com.zenith.control.boot;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Manifest;

/**
 * ZenithHub plugins: loads ZenithProxy and the plugins' jars child-first, so plugin mixins can change any class
 * outside the JDK (ZenithProxy, MCProtocolLib, netty, …).
 * Only this package and ClassTransform itself come from the parent loader.
 */
public final class PluginClassLoader extends URLClassLoader {
    /** Problems while changing classes, for the manager's plugin page. Shared with the application side. */
    public static final List<String> ERRORS = new CopyOnWriteArrayList<>();

    static {
        registerAsParallelCapable();
    }

    private volatile TransformerManager transformers;

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public void setTransformers(TransformerManager transformers) {
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
        return name.startsWith("com.zenith.control.boot.")
            || name.startsWith("net.lenni0451.classtransform.")
            || name.startsWith("org.objectweb.asm.");
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
                ERRORS.add("Couldn't change " + name + ": " + failure);
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
            definePackageFor(name, manifest, source);
            return defineClass(name, transformed, 0, transformed.length, new CodeSource(source, certificates));
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        } catch (LinkageError failure) {
            ERRORS.add("Couldn't load the changed " + name + ": " + failure);
            return super.findClass(name);
        }
    }

    private void definePackageFor(String name, Manifest manifest, URL source) {
        var dot = name.lastIndexOf('.');
        if (dot < 0) return;
        var pkg = name.substring(0, dot);
        if (getDefinedPackage(pkg) != null) return;
        try {
            if (manifest == null) definePackage(pkg, null, null, null, null, null, null, null);
            else definePackage(pkg, manifest, source);
        } catch (IllegalArgumentException ignored) {
            // defined by another thread in the meantime
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
