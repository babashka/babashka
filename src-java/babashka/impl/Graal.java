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

    // API
    public static void setEnv(String name, String value) {
        System.out.println("setenv", name, value);
        try (CCharPointerHolder nameHolder = CTypeConversion.toCString(name);
             CCharPointerHolder valueHolder = CTypeConversion.toCString(value)) {
            setenv(nameHolder.get(), valueHolder.get(), 1);
            System.out.println(System.getenv(name));
        }
        System.out.println(System.getenv(name));
    }

    // public static void main(String[] args) {
    //     setEnv(args[0], args[1]);
    //     System.out.println(System.getenv(args[0]));
    // }
}

