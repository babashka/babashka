package nrepl;

import clojure.lang.IFn;

/**
 * A custom thread implementation that trims the eval callstack further.
 */
public class SessionThread extends Thread {

    IFn runFn;

    public SessionThread(IFn runFn, String name, ClassLoader classLoader) {
        super(null, null, name, 8L * 1024 * 1024); // BB-PATCH the main thread's stack size
        this.runFn = runFn;
        setName(name);
        setContextClassLoader(classLoader);
        setDaemon(true);
    }

    @Override
    public void run() {
        runFn.invoke();
    }
}
