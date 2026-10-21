(ns cider.nrepl.middleware.util.cljs
  (:require
   [cider.nrepl.middleware.util :as util]))

(def ^:private piggieback-mw-sym 'cider.piggieback/wrap-cljs-repl)

(defn try-resolve-piggieback
  "If piggieback is loaded, return `#'cider.piggieback/wrap-cljs-repl`, nil
  otherwise."
  []
  ;; Catch Throwable, not just Exception: loading piggieback pulls in
  ;; ClojureScript, whose recent closure-compiler is Java-21 bytecode, so on an
  ;; older JDK the require fails with an `UnsupportedClassVersionError` (an
  ;; Error). We want to degrade to a Clojure-only setup, not crash at startup.
  (try (requiring-resolve piggieback-mw-sym)
       (catch Throwable _)))

(defn expects-piggieback
  "Deprecated: returns the descriptor unchanged."
  {:deprecated "0.59.0"}
  [descriptor]
  descriptor)

(defn requires-piggieback
  "Deprecated: returns the descriptor unchanged."
  {:deprecated "0.59.0"}
  [descriptor]
  descriptor)

(defn maybe-add-piggieback
  "If piggieback is loaded, conj piggieback mw var to the provided collection."
  [coll]
  (if-some [pb (try-resolve-piggieback)]
    (conj coll pb)
    coll))

(defn maybe-add-piggieback-mware-sym
  "If piggieback is loaded, conj `cider.piggieback/wrap-cljs-repl` symbol to the provided collection."
  [coll]
  (if (try-resolve-piggieback)
    (conj coll piggieback-mw-sym)
    coll))

(defn- maybe-deref
  [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn- session-binding
  "Value bound to the dynamic var named `var-sym` in `msg`'s nREPL session, or
  nil. Uses plain `resolve`, so a backend whose namespaces aren't loaded simply
  yields nil instead of forcing a load. nREPL keeps a session's dynamic bindings
  in the session map keyed by the var object, so the binding is readable outside
  of an eval - which is exactly what the static-analysis ops need."
  [msg var-sym]
  (when-let [v (resolve var-sym)]
    (some-> msg :session maybe-deref (get v))))

;; --- piggieback (the original behaviour) ---------------------------------
;; piggieback stores the compiler-env *atom* in the session under
;; #'cider.piggieback/*cljs-compiler-env*.

(defn- piggieback-cljs-env
  [msg]
  (session-binding msg 'cider.piggieback/*cljs-compiler-env*))

;; --- shadow-cljs (without piggieback) ------------------------------------
;; shadow exposes a build's compiler env via the *public, stable*
;; (shadow.cljs.devtools.api/compiler-env build-id). To learn *which* build a
;; session is on, shadow stamps every message it forwards with a
;; :shadow.cljs.devtools.server.nrepl-impl/build-id key - the integration seam it
;; keeps stable for tooling (its `set-build-id` notes the key is "kept since
;; cider uses it"; clj-suitable, bundled here, reads the very same key). We fall
;; back to shadow's internal *repl-state* session var only if that key is absent.
;;
;; Note current shadow-cljs pulls in piggieback transitively and itself
;; populates piggieback's var (with an equivalent deref handle), so in practice
;; the piggieback provider already handles shadow. This provider is the fallback
;; for a shadow setup where that var isn't populated.

(defn- shadow-build-id
  "The shadow-cljs build id for `msg`'s session, or nil. Prefers the
  `:shadow.cljs.devtools.server.nrepl-impl/build-id` key shadow stamps onto the
  message; falls back to shadow's internal `*repl-state*` session var."
  [msg]
  (or (:shadow.cljs.devtools.server.nrepl-impl/build-id msg)
      (some-> (session-binding msg 'shadow.cljs.devtools.server.nrepl-impl/*repl-state*)
              maybe-deref
              :build-id)))

(defn- shadow-env-handle
  "Adapts shadow's compiler env to the deref-able the rest of the code expects.
  An atom/ref is used as-is; a snapshot map is wrapped in an atom so it both
  derefs (static analysis) and tolerates the analyzer's transient swaps
  (macroexpansion). Returns nil for nil."
  [env]
  (cond
    (nil? env) nil
    (instance? clojure.lang.IDeref env) env
    :else (atom env)))

(defn- shadow-cljs-env
  [msg]
  (when-let [build-id (shadow-build-id msg)]
    (when-let [compiler-env (resolve 'shadow.cljs.devtools.api/compiler-env)]
      (shadow-env-handle (compiler-env build-id)))))

;; --- the provider chain --------------------------------------------------

(def cljs-env-providers
  "Ordered fns of a message that return a deref-able ClojureScript compiler env
  for the session, or nil. First non-nil wins.

  Piggieback is tried first so existing setups behave exactly as before; the
  shadow-cljs provider is a fallback for a pure shadow setup that doesn't load
  piggieback. New backends (figwheel, weasel, ...) plug in here. Rebindable for
  testing."
  [#'piggieback-cljs-env #'shadow-cljs-env])

(defn grab-cljs-env*
  [msg]
  (some #(% msg) cljs-env-providers))

(defn grab-cljs-env
  "Returns the ClojureScript compiler environment for the session's running REPL
  (piggieback or shadow-cljs), or nil when no ClojureScript REPL is active."
  [msg]
  (maybe-deref (grab-cljs-env* msg)))

(defn cljs-response-value
  "Returns the :value slot of an eval response from piggieback as a Clojure
  value."
  [response]
  (let [value (:value response)]
    (try
      (read-string value)
      (catch Exception _
        value))))

(defn response-value
  "Returns the :value slot of an eval response as a Clojure value, reading the
  slot if necessary (piggieback 0.2.x)."
  [msg response]
  (if (grab-cljs-env msg)
    (cljs-response-value response)
    (:value response)))

(defmacro with-cljs-env [msg & body]
  (try
    (require 'cljs.env)
    `(binding [cljs.env/*compiler* (grab-cljs-env* ~msg)]
       ~@body)
    ;; Throwable, not Exception: loading ClojureScript can fail with an
    ;; `UnsupportedClassVersionError` on an older JDK (see `try-resolve-piggieback`).
    (catch Throwable _)))

(defmacro with-cljs-ns [ns-sym & body]
  (try
    (require 'cljs.analyzer)
    `(binding [cljs.analyzer/*cljs-ns* ~ns-sym]
       ~@body)
    (catch Throwable _)))

(defn respond-clojure-only
  "Replies to `msg` signaling that its op is Clojure-only and isn't available
  while a ClojureScript REPL is active, then short-circuits the middleware. Lets
  the client surface a clear message instead of a confusing failure or a
  JVM-only result. See clojure-emacs/cider#2198."
  [{:keys [op] :as msg}]
  (util/respond-to msg
                   :status #{:done :clojure-only}
                   :err (str "The \"" op "\" op is Clojure-only and isn't available in a ClojureScript REPL.\n")))
