package nrepl;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread factory that constructs daemon threads and names them sequentially.
 */
public class DaemonThreadFactory implements ThreadFactory {

    private final AtomicLong counter = new AtomicLong(0);
    private final String nameFormat;
    private final ClassLoader classloader;

    public DaemonThreadFactory(String nameFormat, ClassLoader classloader) {
        this.nameFormat = nameFormat;
        this.classloader = classloader;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName(String.format(nameFormat, counter.getAndIncrement()));
        t.setContextClassLoader(classloader);
        t.setDaemon(true);
        return t;
    }
}
