package babashka.impl;

import clojure.lang.*;
import java.util.Iterator;

/**
 * A map type for SCI's deftype when map interfaces are requested.
 * Extends APersistentMap for full map behavior and delegates method
 * implementations to an IFn methods map. All methods pass 'this' as
 * the first argument, matching Clojure's deftype convention.
 *
 * Fields are stored in a separate map and exposed via ICustomType.getFields(),
 * enabling SCI's .fieldName access.
 */
public class SciMap extends APersistentMap implements IObj, IKVReduce, IMapIterable, Reversible, sci.impl.types.ICustomType {

    private static final Symbol SYM_VAL_AT = Symbol.intern("valAt");
    private static final Symbol SYM_ITERATOR = Symbol.intern("iterator");
    private static final Symbol SYM_CONTAINS_KEY = Symbol.intern("containsKey");
    private static final Symbol SYM_ENTRY_AT = Symbol.intern("entryAt");
    private static final Symbol SYM_COUNT = Symbol.intern("count");
    private static final Symbol SYM_ASSOC = Symbol.intern("assoc");
    private static final Symbol SYM_WITHOUT = Symbol.intern("without");
    private static final Symbol SYM_EMPTY = Symbol.intern("empty");
    private static final Symbol SYM_SEQ = Symbol.intern("seq");
    private static final Symbol SYM_CONS = Symbol.intern("cons");
    private static final Symbol SYM_EQUIV = Symbol.intern("equiv");
    private static final Symbol SYM_TO_STRING = Symbol.intern("toString");
    private static final Symbol SYM_HASHEQ = Symbol.intern("hasheq");
    private static final Symbol SYM_KVREDUCE = Symbol.intern("kvreduce");
    private static final Symbol SYM_KEY_ITERATOR = Symbol.intern("keyIterator");
    private static final Symbol SYM_VAL_ITERATOR = Symbol.intern("valIterator");
    private static final Symbol SYM_RSEQ = Symbol.intern("rseq");
    private static final Symbol SYM_SIZE = Symbol.intern("size");
    private static final Symbol SYM_META = Symbol.intern("meta");
    private static final Symbol SYM_WITH_META = Symbol.intern("withMeta");

    private final IPersistentMap _methods;
    private final IPersistentMap _fields;
    private final Object _interfaces;
    private final Object _protocols;
    private final IPersistentMap _meta;

    public SciMap(IPersistentMap methods, IPersistentMap fields, Object interfaces, Object protocols, IPersistentMap meta) {
        // Normalize symbol keys to unqualified names — macros using
        // syntax-quote may produce namespace-qualified method names
        // (e.g. clojure.core.cache/valAt instead of valAt).
        IPersistentMap normalized = PersistentHashMap.EMPTY;
        for (ISeq s = methods.seq(); s != null; s = s.next()) {
            IMapEntry e = (IMapEntry) s.first();
            Object key = e.key();
            if (key instanceof Symbol) {
                key = Symbol.intern(((Symbol) key).getName());
            }
            normalized = normalized.assoc(key, e.val());
        }
        this._methods = normalized;
        this._fields = fields;
        this._interfaces = interfaces;
        this._protocols = protocols;
        this._meta = meta;
    }

    private IFn method(Symbol name) {
        IFn f = (IFn) _methods.valAt(name);
        if (f == null) throw new UnsupportedOperationException("Method not implemented: " + name);
        return f;
    }

    // ICustomType
    public Object getMethods() { return _methods; }
    public Object getInterfaces() { return _interfaces; }
    public Object getProtocols() { return _protocols; }
    public Object getFields() { return _fields; }

    // IMeta / IObj — delegate to user impl if provided, else use _meta field
    public IPersistentMap meta() {
        IFn f = (IFn) _methods.valAt(SYM_META);
        if (f != null) return (IPersistentMap) f.invoke(this);
        return _meta;
    }
    public IObj withMeta(IPersistentMap meta) {
        IFn f = (IFn) _methods.valAt(SYM_WITH_META);
        if (f != null) return (IObj) f.invoke(this, meta);
        return new SciMap(_methods, _fields, _interfaces, _protocols, meta);
    }

    // Abstract methods from APersistentMap — must be in _methods
    public Object valAt(Object key) {
        return method(SYM_VAL_AT).invoke(this, key);
    }

    public Object valAt(Object key, Object notFound) {
        return method(SYM_VAL_AT).invoke(this, key, notFound);
    }

    public IPersistentMap assoc(Object key, Object val) {
        return (IPersistentMap) method(SYM_ASSOC).invoke(this, key, val);
    }

    public IPersistentMap assocEx(Object key, Object val) {
        if (containsKey(key)) throw Util.runtimeException("Key already present");
        return assoc(key, val);
    }

    public IPersistentMap without(Object key) {
        return (IPersistentMap) method(SYM_WITHOUT).invoke(this, key);
    }

    public boolean containsKey(Object key) {
        return RT.booleanCast(method(SYM_CONTAINS_KEY).invoke(this, key));
    }

    public IMapEntry entryAt(Object key) {
        return (IMapEntry) method(SYM_ENTRY_AT).invoke(this, key);
    }

    public int count() {
        return ((Number) method(SYM_COUNT).invoke(this)).intValue();
    }

    public IPersistentCollection empty() {
        return (IPersistentCollection) method(SYM_EMPTY).invoke(this);
    }

    public ISeq seq() {
        return (ISeq) method(SYM_SEQ).invoke(this);
    }

    public Iterator iterator() {
        return (Iterator) method(SYM_ITERATOR).invoke(this);
    }

    // Non-abstract — optional override, falls back to super
    public int size() {
        IFn f = (IFn) _methods.valAt(SYM_SIZE);
        if (f != null) return ((Number) f.invoke(this)).intValue();
        return super.size();
    }

    public IPersistentCollection cons(Object o) {
        IFn f = (IFn) _methods.valAt(SYM_CONS);
        if (f != null) return (IPersistentCollection) f.invoke(this, o);
        return super.cons(o);
    }

    public boolean equiv(Object o) {
        IFn f = (IFn) _methods.valAt(SYM_EQUIV);
        if (f != null) return RT.booleanCast(f.invoke(this, o));
        return super.equiv(o);
    }

    public String toString() {
        IFn f = (IFn) _methods.valAt(SYM_TO_STRING);
        if (f != null) return (String) f.invoke(this);
        return super.toString();
    }

    public int hasheq() {
        IFn f = (IFn) _methods.valAt(SYM_HASHEQ);
        if (f != null) return ((Number) f.invoke(this)).intValue();
        return super.hasheq();
    }

    // IMapIterable — optional override, falls back to seq-based iteration
    public Iterator keyIterator() {
        IFn f = (IFn) _methods.valAt(SYM_KEY_ITERATOR);
        if (f != null) return (Iterator) f.invoke(this);
        // Default: iterate seq of entries, extract keys
        return new Iterator() {
            ISeq s = seq();
            public boolean hasNext() { return s != null; }
            public Object next() {
                IMapEntry e = (IMapEntry) s.first();
                s = s.next();
                return e.key();
            }
        };
    }

    public Iterator valIterator() {
        IFn f = (IFn) _methods.valAt(SYM_VAL_ITERATOR);
        if (f != null) return (Iterator) f.invoke(this);
        // Default: iterate seq of entries, extract vals
        return new Iterator() {
            ISeq s = seq();
            public boolean hasNext() { return s != null; }
            public Object next() {
                IMapEntry e = (IMapEntry) s.first();
                s = s.next();
                return e.val();
            }
        };
    }

    // Reversible — optional override, falls back to reversing seq
    public ISeq rseq() {
        IFn f = (IFn) _methods.valAt(SYM_RSEQ);
        if (f != null) return (ISeq) f.invoke(this);
        // Default: reverse the seq
        ISeq reversed = null;
        for (ISeq s = seq(); s != null; s = s.next()) {
            reversed = RT.cons(s.first(), reversed);
        }
        return reversed;
    }

    public Object kvreduce(IFn f, Object init) {
        IFn kvr = (IFn) _methods.valAt(SYM_KVREDUCE);
        if (kvr != null) return kvr.invoke(this, f, init);
        // Default: iterate seq of entries
        for (ISeq s = seq(); s != null; s = s.next()) {
            IMapEntry e = (IMapEntry) s.first();
            init = f.invoke(init, e.key(), e.val());
            if (RT.isReduced(init)) return ((IDeref) init).deref();
        }
        return init;
    }
}
