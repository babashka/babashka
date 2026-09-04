package babashka.impl;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Keeps run-time loading of Clojure source out of the native image.
 *
 * Every run-time require or load ends in RT.load, and from there the reader
 * and compiler are reachable. bb's compiled code loads nothing at run time;
 * scripts go through sci. A reachable RT.load costs about a megabyte of
 * compiler; with the body replaced it costs nothing and fails loudly.
 */
@TargetClass(clojure.lang.RT.class)
final class Target_clojure_lang_RT {

    @Substitute
    public static void load(String scriptbase) {
        throw NamespaceLookup.unsupported(scriptbase, "RT.load");
    }

    @Substitute
    public static void load(String scriptbase, boolean failIfNotFound) {
        throw NamespaceLookup.unsupported(scriptbase, "RT.load");
    }
}
