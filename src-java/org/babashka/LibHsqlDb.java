package org.babashka;

public class LibHsqlDb {
    private static native String evalString(long isolateThreadId, String expr);
    private static native long createIsolate();
    private static native void tearDownIsolate(long id);

    public static String eval(String expr) {
        long isolate = createIsolate();
        String x = evalString(isolate, expr);
        tearDownIsolate(isolate);
        return x;
    }
}

