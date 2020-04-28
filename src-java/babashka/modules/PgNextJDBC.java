package babashka.modules;

public class PgNextJDBC {
    private static native String initPG();

    public static String init() {
        return initPG();
    }
}
