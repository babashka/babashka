package babashka.impl;

import java.util.concurrent.atomic.AtomicReference;

import clojure.lang.IPersistentMap;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Keeps the namespace mapping tables out of the native image.
 *
 * native-image follows a heap field only when reachable code reads it. bb
 * never reads Namespace.mappings at run time, so the vars in those tables
 * and the function objects in their roots are dead heap. A single reachable
 * read, a run-time resolve for instance, makes every var of every loaded
 * namespace live and grows the image by tens of megabytes. With the field
 * reset and every reader replaced, such a read fails loudly instead.
 *
 * Only the image is affected. Build-time code runs the original class.
 */
final class NamespaceLookup {
    static UnsupportedOperationException unsupported(Object sym, Object ns) {
        return new UnsupportedOperationException(
            "Run-time lookup of " + sym + " in namespace " + ns
            + " is not supported in this binary. Resolve at build time.");
    }
}

@TargetClass(clojure.lang.Namespace.class)
final class Target_clojure_lang_Namespace {

    @Alias
    public Symbol name;

    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)
    AtomicReference<IPersistentMap> mappings;

    @Substitute
    public IPersistentMap getMappings() {
        throw NamespaceLookup.unsupported("mappings", name);
    }

    @Substitute
    public Var intern(Symbol sym) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    Object reference(Symbol sym, Object val) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    Class referenceClass(Symbol sym, Class val) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    public void unmap(Symbol sym) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    public Class importClass(Symbol sym, Class c) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    public Var refer(Symbol sym, Var var) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    public Object getMapping(Symbol sym) {
        throw NamespaceLookup.unsupported(sym, name);
    }

    @Substitute
    public Var findInternedVar(Symbol sym) {
        throw NamespaceLookup.unsupported(sym, name);
    }
}
