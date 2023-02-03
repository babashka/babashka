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

    public URLClassLoader(java.net.URL[] urls) {
        super(urls);
    }

    public URLClassLoader(java.net.URL[] urls, java.net.URLClassLoader parent) {
        super(urls, parent);
    }

    public void _addURL(java.net.URL url) {
        super.addURL(url);
    }

    // calling super.getResource() returned nil in native-image
    public java.net.URL getResource(String name) {
        return findResource(name);
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
