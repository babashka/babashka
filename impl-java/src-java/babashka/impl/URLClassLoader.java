package babashka.impl;

public class URLClassLoader extends java.net.URLClassLoader {

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

    public java.io.InputStream getResourceAsStream(String name) {
        System.out.println("name: " + name);
        var stream = super.getResourceAsStream(name);
        System.out.println("stream: " + stream);
        return super.getResourceAsStream(name);
    }

    public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
        return findResources(name);
    }

    public void close() {
        System.out.println("close");
    }

}
