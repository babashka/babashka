public class JavaRecur {

    public static void foo(int x) {
        System.out.println(x);
        foo(x+1);
    }

    public static void main(String [] args) {
        foo(0);
    }
}
