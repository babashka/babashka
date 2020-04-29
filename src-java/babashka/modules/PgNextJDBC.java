package babashka.modules;

public class PgNextJDBC {
    private static native Object initPG(long isolateThreadId, int x, int y);
    private static native long createIsolate();
    //private static native void tearDownIsolate(long isolate);

    public static void init(int a, int b) {
        long isolate = createIsolate();
        Object x = initPG(isolate, a, b);
        System.out.println("hello");
        System.out.println(x);
        System.out.flush();
        //tearDownIsolate(isolate);
    }
}
