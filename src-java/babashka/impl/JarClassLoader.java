package babashka.impl;

import clojure.lang.DynamicClassLoader;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Collections;

/**
 * A classloader that can find resources and classes in JAR files and
 * directories, working around URLClassLoader.findResource() not functioning
 * in GraalVM native images with Crema/RuntimeClassLoading.
 *
 * Uses JarFile directly to read JAR contents, which works in native images.
 */
public class JarClassLoader extends DynamicClassLoader {

    private final List<JarFile> jarFiles = new ArrayList<>();
    private final List<Path> dirPaths = new ArrayList<>();
    // Maps resource name -> first JarFile for fast lookup
    public final Map<String, JarFile> resourceIndex = new HashMap<>();
    // Maps resource name -> all JarFiles containing it
    private final Map<String, List<JarFile>> resourceIndexAll = new HashMap<>();

    public JarClassLoader(String[] paths, ClassLoader parent) {
        super(parent);
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.isDirectory(path)) {
                dirPaths.add(path);
            } else if (Files.isRegularFile(path) && p.endsWith(".jar")) {
                try {
                    JarFile jf = new JarFile(p);
                    jarFiles.add(jf);
                    Enumeration<JarEntry> entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (!entry.isDirectory()) {
                            resourceIndex.putIfAbsent(entry.getName(), jf);
                            resourceIndexAll.computeIfAbsent(entry.getName(), k -> new ArrayList<>()).add(jf);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Cannot open JAR: " + p, e);
                }
            }
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        // Check directories first
        for (Path dir : dirPaths) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    return Files.newInputStream(file);
                } catch (IOException e) {
                    // fall through
                }
            }
        }
        // Check JARs
        JarFile jf = resourceIndex.get(name);
        if (jf != null) {
            try {
                JarEntry entry = jf.getJarEntry(name);
                if (entry != null) {
                    return jf.getInputStream(entry);
                }
            } catch (IOException e) {
                // fall through
            }
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public URL getResource(String name) {
        // Check directories first
        for (Path dir : dirPaths) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    return file.toUri().toURL();
                } catch (MalformedURLException e) {
                    // fall through
                }
            }
        }
        // Check JARs
        JarFile jf = resourceIndex.get(name);
        if (jf != null) {
            try {
                return new URL("jar:file:" + jf.getName() + "!/" + name);
            } catch (MalformedURLException e) {
                // fall through
            }
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>();
        // Check directories
        for (Path dir : dirPaths) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                urls.add(file.toUri().toURL());
            }
        }
        // Check JARs
        List<JarFile> jfs = resourceIndexAll.get(name);
        if (jfs != null) {
            for (JarFile jf : jfs) {
                urls.add(new URL("jar:file:" + jf.getName() + "!/" + name));
            }
        }
        return Collections.enumeration(urls);
    }

    private static boolean parentOnly(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
            || name.startsWith("jdk.") || name.startsWith("sun.")
            || name.startsWith("clojure.");
    }

    // Load jar classes child-first so unregistered image copies do not shadow them.
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!parentOnly(name) && resourceIndex.containsKey(name.replace('.', '/') + ".class")) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";

        // Check directories
        for (Path dir : dirPaths) {
            Path file = dir.resolve(path);
            if (Files.isRegularFile(file)) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    // fall through
                }
            }
        }

        // Check JARs
        JarFile jf = resourceIndex.get(path);
        if (jf != null) {
            try {
                JarEntry entry = jf.getJarEntry(path);
                if (entry != null) {
                    byte[] bytes = jf.getInputStream(entry).readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (IOException e) {
                // fall through
            }
        }

        return super.findClass(name);
    }
}
