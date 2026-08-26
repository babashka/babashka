package babashka.impl;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/** The two libffi entry points for the calls that the fixed native call
 * shapes cannot make, bound at link time. Only functional in a native
 * image, and only in one that links libffi: without the archive the symbols
 * do not resolve. babashka.impl.libffi, the only caller, is on the
 * classpath only for a build that links the archive, which keeps these
 * methods unreachable otherwise. See script/setup-libffi. */
public final class Libffi {
    private Libffi() {}

    // TO_NATIVE, the default and what the generated trampolines use:
    // ffi_call runs arbitrary C, which may block or call back into Java
    // through a callback, and NO_TRANSITION is for leaf functions that do
    // neither.
    @CFunction(value = "ffi_prep_cif", transition = Transition.TO_NATIVE)
    private static native int ffi_prep_cif(PointerBase cif, int abi, int nargs,
                                           PointerBase rtype, PointerBase atypes);

    @CFunction(value = "ffi_call", transition = Transition.TO_NATIVE)
    private static native void ffi_call(PointerBase cif, PointerBase fn,
                                        PointerBase rvalue, PointerBase avalues);

    public static int prepCif(long cif, int abi, int nargs, long rtype, long atypes) {
        return ffi_prep_cif(WordFactory.pointer(cif), abi, nargs,
                            WordFactory.pointer(rtype), WordFactory.pointer(atypes));
    }

    public static void call(long cif, long fn, long rvalue, long avalues) {
        ffi_call(WordFactory.pointer(cif), WordFactory.pointer(fn),
                 WordFactory.pointer(rvalue), WordFactory.pointer(avalues));
    }
}
