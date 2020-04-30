package org.babashka;

import org.graalvm.nativeimage.c.type.CCharPointer;
import com.oracle.svm.core.c.CConst;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.Pointer;

public final class LibHsqlDb {
    private static native CCharPointer evalString(long isolateThreadId);
    private static native long createIsolate();
    private static native void tearDownIsolate(long id);

    private static String toJavaStringUnchecked(CCharPointer cString, UnsignedWord length) {
        byte[] bytes = new byte[(int) length.rawValue()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = ((Pointer) cString).readByte(i);
        }
        return new String(bytes);
    }

    public static UnsignedWord strlen(CCharPointer str) {
        UnsignedWord n = WordFactory.zero();
        while (((Pointer) str).readByte(n) != 0) {
            n = n.add(1);
        }
        return n;
    }

    public static String toJavaString(CCharPointer cString) {
        if (cString.isNull()) {
            return null;
        } else {
            return toJavaStringUnchecked(cString, strlen(cString));
        }
    }

    public static void eval(String expr) {
        System.out.println("x");
        System.out.flush();
        long isolate = createIsolate();
        System.out.println("y");
        System.out.flush();
        CCharPointer c = evalString(isolate);
        String x = toJavaString(c);
        //System.out.println("x: " + x);
        System.out.flush();
        //tearDownIsolate(isolate);
        //return x;
    }
}

