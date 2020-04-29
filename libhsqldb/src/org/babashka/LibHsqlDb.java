package org.babashka;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import com.oracle.svm.core.c.CConst;
import org.graalvm.word.Pointer;

public final class LibHsqlDb {
    private static String result = "result";

    @CEntryPoint(name = "Java_org_babashka_LibHsqlDb_evalString")
    public static
        @CConst CCharPointer evalString(Pointer jniEnv,
                                        Pointer clazz,
                                        @CEntryPoint.IsolateThreadContext long isolateId) {
        System.out.println("debug: xxx");
        // String expr = CTypeConversion.toJavaString(s);
        //System.out.println("debug: " + expr);
        //System.out.flush();;
        //String result = "hello from lib"; // org.babashka.libhsqldb.evalString(expr);
        CTypeConversion.CCharPointerHolder holder = CTypeConversion.toCString(result);
        CCharPointer value = holder.get();
        return value;
    }

    @CEntryPoint(name = "Java_org_babashka_LibHsqlDb_createIsolate", builtin=CEntryPoint.Builtin.CREATE_ISOLATE)
    public static native long createIsolate();

    @CEntryPoint(name = "Java_org_babashka_LibHsqlDb_tearDownIsolate", builtin=CEntryPoint.Builtin.TEAR_DOWN_ISOLATE)
    public static native void tearDownIsolate(@CEntryPoint.IsolateThreadContext long isolateId);

}
