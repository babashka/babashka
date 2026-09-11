package nrepl;

import clojure.lang.IFn;

/**
 * A custom thread implementation that trims the eval callstack further.
 */
public class SessionThread extends Thread {

    IFn runFn;

    public SessionThread(IFn runFn, String name, ClassLoader classLoader) {
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
