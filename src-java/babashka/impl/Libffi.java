package babashka.impl;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/** Libffi entry points that bind at link time.
 * Native images that link libffi make this class reachable. */
public final class Libffi {
    private Libffi() {}

    // ffi_call can block or call Java through a callback.
    @CFunction(value = "ffi_prep_cif", transition = Transition.TO_NATIVE)
    private static native int ffi_prep_cif(PointerBase cif, int abi, int nargs,
                                           PointerBase rtype, PointerBase atypes);

    @CFunction(value = "ffi_call", transition = Transition.TO_NATIVE)
    private static native void ffi_call(PointerBase cif, PointerBase fn,
                                        PointerBase rvalue, PointerBase avalues);

    // This leaf function returns a constant.
    @CFunction(value = "ffi_get_version", transition = Transition.NO_TRANSITION)
    private static native CCharPointer ffi_get_version();

    /** Returns the linked libffi version. */
    public static String version() {
        return CTypeConversion.toJavaString(ffi_get_version());
    }

    public static int prepCif(long cif, int abi, int nargs, long rtype, long atypes) {
        return ffi_prep_cif(WordFactory.pointer(cif), abi, nargs,
                            WordFactory.pointer(rtype), WordFactory.pointer(atypes));
    }

    public static void call(long cif, long fn, long rvalue, long avalues) {
        ffi_call(WordFactory.pointer(cif), WordFactory.pointer(fn),
                 WordFactory.pointer(rvalue), WordFactory.pointer(avalues));
    }
}
