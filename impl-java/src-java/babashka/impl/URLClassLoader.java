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
}
