package babashka.impl;

import clojure.lang.ISeq;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Keeps run-time require, use and load out of the native image at the entry
 * points, so none of the loading machinery in clojure.core is reachable.
 * bb's compiled code loads nothing at run time; scripts go through sci.
 */
@TargetClass(className = "clojure.core$require")
final class Target_clojure_core_require {
    @Substitute
    public static Object invokeStatic(ISeq args) {
        throw NamespaceLookup.unsupported(args, "clojure.core/require");
    }
}

@TargetClass(className = "clojure.core$use")
final class Target_clojure_core_use {
    @Substitute
    public static Object invokeStatic(ISeq args) {
        throw NamespaceLookup.unsupported(args, "clojure.core/use");
    }
}

@TargetClass(className = "clojure.core$load")
final class Target_clojure_core_load {
    @Substitute
    public static Object invokeStatic(ISeq paths) {
        throw NamespaceLookup.unsupported(paths, "clojure.core/load");
    }
}
