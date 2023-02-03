package babashka.impl;

import java.util.WeakHashMap;
import java.io.*;
import java.util.Objects;
import java.net.*;
import java.util.jar.*;

public class URLClassLoader extends java.net.URLClassLoader implements Closeable {

    public URLClassLoader(java.net.URL[] urls) {
        super(urls);
    }

    public URLClassLoader(java.net.URL[] urls, java.net.URLClassLoader parent) {
        super(urls, parent);
    }

    public void _addURL(java.net.URL url) {
        super.addURL(url);
    }

    public java.net.URL getResource(String name) {
        System.out.println("getResource name: " + name);
        var res = super.getResource(name);
        System.out.println("res: " + res);
        return findResource(name);
    }

    private WeakHashMap<Closeable,Void>
        closeables = new WeakHashMap<>();

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
            } else if (true) { // }(urlc instanceof sun.net.www.protocol.file.FileURLConnection) {
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
        System.out.println("close");
        super.close();

        java.util.List<IOException> errors = new java.util.ArrayList<IOException>();

        // now close any remaining streams.

        synchronized (closeables) {
            java.util.Set<Closeable> keys = closeables.keySet();
            for (Closeable c : keys) {
                try {
                    c.close();
                } catch (IOException ioex) {
                    errors.add(ioex);
                }
            }
            closeables.clear();
        }

        if (errors.isEmpty()) {
            return;
        }

        IOException firstex = errors.remove(0);

        // Suppress any remaining exceptions

        for (IOException error: errors) {
            firstex.addSuppressed(error);
        }
        throw firstex;
    }

}
