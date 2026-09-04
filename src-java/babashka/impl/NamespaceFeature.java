package babashka.impl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

import clojure.lang.IPersistentMap;
import clojure.lang.Namespace;
import clojure.lang.PersistentHashMap;
import clojure.lang.Symbol;
import clojure.lang.Var;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;

/**
 * Prunes the namespace mapping tables to the vars the image contains.
 *
 * Compiled Clojure keeps every var it references in a static Var field of
 * the referencing class. A reachability handler on each such field collects
 * the vars that reachable code touches. After analysis, Namespace.mappings
 * is replaced with a table of just those vars, so a run-time resolve finds
 * a var that is in the image and nil for any other, and the table adds no
 * objects the image did not already hold.
 */
public final class NamespaceFeature implements Feature {

    private final Map<Symbol, Map<Symbol, Var>> live = new ConcurrentHashMap<>();
    private volatile boolean analysisDone;
    private int fields;

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        ClassLoader loader = access.getApplicationClassLoader();
        for (Path p : access.getApplicationClassPath()) {
            if (!p.toString().endsWith(".jar")) {
                continue;
            }
            try (JarFile jar = new JarFile(p.toFile())) {
                jar.stream()
                    .map(e -> e.getName())
                    .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF"))
                    .forEach(n -> registerVarFields(access, loader,
                                                    n.substring(0, n.length() - 6).replace('/', '.')));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        Field mappings;
        try {
            mappings = Namespace.class.getDeclaredField("mappings");
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        access.registerFieldValueTransformer(mappings, new FieldValueTransformer() {
            @Override
            public boolean isAvailable() {
                return analysisDone;
            }

            @Override
            public Object transform(Object receiver, Object original) {
                Namespace ns = (Namespace) receiver;
                Map<Symbol, Var> vars = live.getOrDefault(ns.name, Collections.emptyMap());
                IPersistentMap m = PersistentHashMap.EMPTY;
                for (Map.Entry<Symbol, Var> e : vars.entrySet()) {
                    m = m.assoc(e.getKey(), e.getValue());
                }
                return new AtomicReference<IPersistentMap>(m);
            }
        });
        System.out.println("NamespaceFeature: watching " + fields + " var fields");
    }

    private void registerVarFields(BeforeAnalysisAccess access, ClassLoader loader, String className) {
        Class<?> c;
        Field[] declared;
        try {
            c = Class.forName(className, false, loader);
            declared = c.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }
        for (Field f : declared) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != Var.class) {
                continue;
            }
            fields++;
            access.registerReachabilityHandler(a -> record(f), f);
        }
    }

    private void record(Field f) {
        try {
            f.setAccessible(true);
            Object o = f.get(null);
            if (o instanceof Var) {
                Var v = (Var) o;
                if (v.ns != null && v.sym != null) {
                    live.computeIfAbsent(v.ns.name, k -> new ConcurrentHashMap<>()).put(v.sym, v);
                }
            }
        } catch (Throwable t) {
            // class failed to initialize or the field is unset: nothing to keep
        }
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        analysisDone = true;
        int n = live.values().stream().mapToInt(Map::size).sum();
        System.out.println("NamespaceFeature: kept " + n + " vars in " + live.size() + " namespaces");
    }
}
