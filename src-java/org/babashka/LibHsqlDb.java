package org.babashka;

public final class LibHsqlDb {
    private static native String evalString(long isolateThreadId);
    private static native long createIsolate();
    private static native void tearDownIsolate(long id);

    public static String eval(String expr) {
        System.out.println("x");
        System.out.flush();
        long isolate = createIsolate();
        System.out.println("y");
        System.out.flush();
        String x = evalString(isolate);
        System.out.println("x: " + x);
        System.out.flush();
        //tearDownIsolate(isolate);
        return x;
    }
}

