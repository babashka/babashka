package babashka.impl;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.type.CCharPointer;
import com.oracle.svm.core.c.ProjectHeaderFile;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import java.util.List;
import java.util.Collections;

@CContext(Graal.MyDirectives.class)
public class Graal {

    static class MyDirectives implements CContext.Directives {

        @Override
        public List<String> getHeaderFiles() {
            return Collections.singletonList("<stdlib.h>");
        }
    }

    @CFunction
    private static native int setenv(CCharPointer name, CCharPointer value, int overwrite);

    @CFunction
    private static native CCharPointer getenv(CCharPointer name);

    // Public API

    public static boolean setEnv(String name, String value) {
        return setEnv(name, value, true);
    }

    public static boolean setEnv(String name, String value, boolean overwrite) {
        boolean ret = false;
        try (CCharPointerHolder nameHolder = CTypeConversion.toCString(name)) {
            CCharPointerHolder valueHolder = CTypeConversion.toCString(value);
            int w = (overwrite ? 1 : 0);
            int cRet = setenv(nameHolder.get(), valueHolder.get(), w);
            ret = (cRet == 0 ? true : false);
            return ret;
        }
    }

    public static String getEnv(String name) {
        try (CCharPointerHolder nameHolder = CTypeConversion.toCString(name);) {
            CCharPointer p = getenv(nameHolder.get());
            String ret = CTypeConversion.toJavaString(p);
            return ret;
        }
    }

    public static void main(String[] args) {
        System.out.println(setEnv(args[0], args[1]));
        System.out.println(getEnv(args[0]));
    }
}
