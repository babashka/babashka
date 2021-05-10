public class JavaRecur {

    public static void foo(int x) {
        if (x % 10000 == 0)
            System.out.println(x);
        foo(x+1);
    }

    public static void main(String [] args) throws InterruptedException {
        Runnable runnable =
            () -> { foo(0); };

        Thread thread = new Thread(null, runnable, "foo", 10000000);
        thread.start();
        thread.join();
    }
}
