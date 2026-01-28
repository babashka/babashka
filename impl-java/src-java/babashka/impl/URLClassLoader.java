// This file is mostly a workaround for https://github.com/oracle/graal/issues/1956

package babashka.impl;

import java.util.WeakHashMap;
import java.io.*;
import java.util.Objects;
import java.net.*;
import java.util.jar.*;

public class URLClassLoader extends java.net.URLClassLoader implements Closeable {

    private WeakHashMap<Closeable,Void>
        closeables = new WeakHashMap<>();

    // The original classloader to delegate to for resources not found in this loader
    private ClassLoader fallbackClassLoader;

    public URLClassLoader(java.net.URL[] urls, ClassLoader fallback) {
        super(urls);
        this.fallbackClassLoader = fallback;
    }

    public void _addURL(java.net.URL url) {
        super.addURL(url);
    }

    // calling super.getResource() returned nil in native-image
    public java.net.URL getResource(String name) {
        java.net.URL url = findResource(name);
        // Only fall back for JLine SPI resources
        // Falling back for all resources is slow due to native-image classloader
        if (url == null && fallbackClassLoader != null
                && name.startsWith("META-INF/services/org/jline/")) {
            url = fallbackClassLoader.getResource(name);
        }
        return url;
    }

    // calling super.getResourceAsStream() returned nil in native-image
    public InputStream getResourceAsStream(String name) {
        Objects.requireNonNull(name);
        URL url = getResource(name);
        try {
            if (url == null) {
                return null;
            }
            URLConnection urlc = url.openConnection();
            InputStream is = urlc.getInputStream();
            if (urlc instanceof JarURLConnection) {
                JarFile jar = ((JarURLConnection)urlc).getJarFile();
                synchronized (closeables) {
                    if (!closeables.containsKey(jar)) {
                        closeables.put(jar, null);
                    }
                }
            } else {
                synchronized (closeables) {
                    closeables.put(is, null);
                }
            }
            return is;
        } catch (IOException e) {
            return null;
        }
    }

    public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
        return findResources(name);
    }

    public void close() throws IOException {
        super.close();

        java.util.List<IOException> errors = new java.util.ArrayList<IOException>();

        synchronized (closeables) {
            java.util.Set<Closeable> keys = closeables.keySet();
            for (Closeable c : keys) {
                try {
                    c.close();
                } catch (IOException ex) {
                    errors.add(ex);
                }
            }
            closeables.clear();
        }

        if (errors.isEmpty()) {
            return;
        }

        IOException firstEx = errors.remove(0);

        for (IOException error: errors) {
            firstEx.addSuppressed(error);
        }
        throw firstEx;
    }

}
