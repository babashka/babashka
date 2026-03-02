# Issue #1874: prefer-method on print-method doesn't work

https://github.com/babashka/babashka/issues/1874

## Root cause

In SCI, certain interfaces like `clojure.lang.IDeref` are exposed as
"protocol-like" class descriptors — maps with `:class`, `:methods`, and `:ns`
keys — rather than as raw Java classes. For example, after
`(import [clojure.lang IDeref])`, `IDeref` resolves to:

```clojure
{:class clojure.lang.IDeref, :methods #{...}, :ns #<Namespace clojure.lang>}
```

When the user calls `(prefer-method print-method Future IDeref)`, the `IDeref`
argument is this map, not `clojure.lang.IDeref` the Java interface. The
preference is stored with the map as the key. But when `print-method` dispatches,
it uses real Java classes, so the preference is never matched.

Confirmed: `(prefer-method print-method Future (:class IDeref))` works.

`Future` (a concrete class, not a SCI protocol) resolves to the real
`java.util.concurrent.Future` class, so it doesn't have this problem.

## Exact changes needed

### 1. `sci/src/sci/impl/multimethods.cljc`

Add `unwrap-class` helper before `multi-fn-add-method-impl` and use it there:

```clojure
;; ADD before multi-fn-add-method-impl (around line 100):
(defn unwrap-class
  "If x is a SCI class descriptor map (has :class key), return the actual class.
  Otherwise return x unchanged."
  [x]
  #?(:clj (if (and (map? x) (contains? x :class))
            (:class x)
            x)
     :cljs x))

;; CHANGE multi-fn-add-method-impl to unwrap:
(defn multi-fn-add-method-impl
  [multifn dispatch-val f]
  (let [dispatch-val (unwrap-class dispatch-val)]
    #?(:clj (.addMethod ^clojure.lang.MultiFn multifn dispatch-val f)
       :cljs (-add-method multifn dispatch-val f))))
```

### 2. `sci/src/sci/impl/namespaces.cljc`

Replace the `copy-core-var` entries for `prefer-method` and `remove-method`
(around line 1297) with wrapped versions that call `unwrap-class` on dispatch
values:

```clojure
;; BEFORE:
     'prefer-method (copy-core-var prefer-method)
     ...
     'remove-method (copy-core-var remove-method)

;; AFTER: wrap to unwrap SCI class descriptor maps
     'prefer-method <wrapped-var-that-calls-unwrap-class-on-both-dispatch-vals>
     ...
     'remove-method <wrapped-var-that-calls-unwrap-class-on-dispatch-val>
```

The wrapping functions are straightforward:
- `prefer-method`: `(fn [multifn x y] (prefer-method multifn (unwrap-class x) (unwrap-class y)))`
- `remove-method`: `(fn [multifn dv] (remove-method multifn (unwrap-class dv)))`

## Blocker: `new-var` metadata

The wrapped vars must have docstrings (the `docstrings-test` in
`sci.namespaces-test` checks all `clojure.core` vars have `:doc` metadata).

First attempt used:
```clojure
(new-var 'prefer-method (fn ...) (assoc (meta #'prefer-method) :ns clojure-core-ns))
```

This failed with:
```
Assert failed: prefer-method
(and (not (boolean? ns)) (instance? sci.lang.Namespace ns))
```

Because `(meta #'prefer-method)` has `:ns` as a Clojure namespace, not a
`sci.lang.Namespace`. Need to figure out how `copy-core-var` (a macro) handles
this — it likely builds the metadata differently. Look at:
- `sci/src/sci/impl/copy_vars.cljc` for `copy-core-var` and `new-var`
- How other wrapped vars in `namespaces.cljc` pass metadata

Possible fix: pass metadata as a map with `:doc` string and `:ns clojure-core-ns`
directly:
```clojure
(new-var 'prefer-method (fn ...) {:doc "Causes the multimethod..." :ns clojure-core-ns})
```

## Test to add

Test with a user-defined multimethod + SCI protocol dispatch values. Doesn't
need `print-method` specifically. Something like:

```clojure
(deftest prefer-method-with-protocol-dispatch-test
  (testing "prefer-method works with SCI class descriptor maps"
    (is (= :future
           (tu/eval* "
(import [java.util.concurrent Future] [clojure.lang IDeref])
(defmulti my-print type)
(defmethod my-print Future [_] :future)
(defmethod my-print IDeref [_] :ideref)
(prefer-method my-print Future IDeref)
(my-print (deref (future (reify java.util.concurrent.Future
  ...))))" {})))))
```

Or more simply, just test that `(prefers ...)` contains the right class after
`prefer-method` with a SCI class descriptor.
