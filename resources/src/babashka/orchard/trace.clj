(ns orchard.trace
  "Faster and prettier reimplementation of `clojure.tools.trace` with unnecessary
  parts removed. Used for tracing function invocations and their results."
  (:require [clojure.string :as str]
            [orchard.print :as print]))

;;;; Internals

(defmacro ^:private appendn
  "Add all strings in `args` to the given StringBuilder `sb`."
  [sb & args]
  (let [sbsym (with-meta (gensym "sb") {:tag 'StringBuilder})]
    `(let [~sbsym ~sb]
       ~@(map (fn [x] `(.append ~sbsym ~x)) args))))

(defn- funcall-to-string
  "Print the invocation of a function and arguments to a string."
  ([fname args]
   (funcall-to-string fname args ""))
  ([fname, args, ^String outer-prefix]
   (let [max-args 20 ;; Most args we want to display before truncating.
         res (StringBuilder.)
         argn (bounded-count (inc max-args) args)
         more-than-max-args? (> argn max-args)
         argn (min argn max-args)]
     (appendn res outer-prefix "(" fname)
     (dotimes [i argn]
       (let [arg (nth args i)]
         (appendn res " " arg)))
     (when more-than-max-args?
       (.append res " ..."))
     (.append res ")")
     (.toString res))))

(def ^:private ^:dynamic *depth* 0)

(defn- trace-indent []
  (str/join (repeat *depth* "│ ")))

(defmacro ^:private limit-printing [& body]
  ;; Good defaults for orchard.print.
  `(binding [*print-length* 100
             *print-level* 5
             print/*max-atom-length* 150
             print/*max-total-length* 10000]
     ~@body))

(def ^:private listeners
  "Set of listener fns, each called with a structured trace event map."
  (atom #{}))

(def ^:private output-mode
  "How trace output is delivered: `:repl` (println, the default), `:listeners`
  (structured events to registered listeners) or `:both`."
  (atom :repl))

(def ^:private call-id
  "Counter handing each traced invocation a unique id, pairing its call and
  return events."
  (atom 0))

(defn- print-to-repl? []
  (contains? #{:repl :both} @output-mode))

(defn- emit-events? []
  (and (contains? #{:listeners :both} @output-mode)
       (seq @listeners)))

(defn- emit [event]
  (run! (fn [f] (f event)) @listeners))

(def ^:private max-event-args
  "Most args to include in a structured trace event before truncating."
  20)

(defn- event-args
  "Return up to `max-event-args` printed args, with a trailing \"...\" when
  there are more.  Bounding the count keeps lazy or infinite arglists (e.g.
  `(apply f (range))`) from being fully realized."
  [args]
  (let [shown (into [] (comp (take max-event-args) (map print/print-str)) args)]
    (cond-> shown
      (> (bounded-count (inc max-event-args) args) max-event-args)
      (conj "..."))))

(def ^:private ^:dynamic *tracing*
  "True while orchard.trace prints or sends a traced call, when the traced fns
  of the namespaces it calls into go straight to the fn."
  false)

(defn- tooling-ns?
  "Whether `ns` is one that tracing calls into, printing a call or sending it
  to the listeners: Clojure's and orchard's, and nREPL's and cider-nrepl's for
  cider-nrepl's listener."
  [ns]
  (let [ns-str (str ns)]
    (boolean (some #(str/starts-with? ns-str %)
                   ["clojure." "orchard." "nrepl." "cider."]))))

(defmacro ^:private with-interop-bindings
  "Like `binding`, for a map of vars to values, without calling `hash-map` or
  `push-thread-bindings`, which may be traced."
  [bindings & body]
  `(do (clojure.lang.Var/pushThreadBindings ~bindings)
       (try ~@body
            (finally (clojure.lang.Var/popThreadBindings)))))

(defn- throwable-str [^Throwable e]
  (cond-> (.getName (class e))
    (ex-message e) (str ": " (ex-message e))))

(defn- trace-call [name ^clojure.lang.IFn f ^clojure.lang.ISeq args]
  ;; Good defaults for orchard.print.
  (let [repl? (print-to-repl?)
        events? (emit-events?)
        id (when events? (swap! call-id inc))
        depth *depth*
        sname (str name)]
    (limit-printing
     (when repl?
       (println (trace-indent))
       (println (funcall-to-string sname (map print/print-str args)
                                   (trace-indent))))
     (when events?
       (emit {:id id :phase :call :name sname :depth depth
              :args (event-args args)})))
    (let [[value thrown] (with-interop-bindings {#'*tracing* false
                                                 #'*depth* (inc *depth*)}
                           ;; Not apply, which may be traced.
                           (try [(.applyTo f args)]
                                (catch Throwable e [nil e])))]
      (limit-printing
       (let [shown (if thrown (throwable-str thrown) (print/print-str value))]
         (when repl?
           (binding [*depth* (inc *depth*)]
             (println (trace-indent)))
           (println (str (trace-indent) (if thrown "└─✗ " "└─→ ") shown)))
         (when events?
           (emit (if thrown
                   {:id id :phase :throw :name sname :depth depth :error shown}
                   {:id id :phase :return :name sname :depth depth :value shown})))))
      (if thrown (throw thrown) value))))

(defn- trace-fn-call [name tooling? ^clojure.lang.IFn f ^clojure.lang.ISeq args]
  (if (and tooling? *tracing*)
    ;; Most likely a call from orchard.trace's own code, which tracing would
    ;; recurse into. Interop only, since anything else may be traced too.
    (.applyTo f args)
    (with-interop-bindings {#'*tracing* true}
      (trace-call name f args))))

(defn- resolve-var ^clojure.lang.Var [v]
  (if (var? v) v (resolve v)))

;;;; Public API

(def ^:private traced-vars (atom #{}))
(def ^:private traced-nses (atom #{}))

(defn add-trace-listener
  "Register F to be called with each structured trace event, and return F.
  An event is a map with keys `:id`, `:phase` (`:call`, `:return` or `:throw`),
  `:name`, `:depth`, and `:args` (on `:call`), `:value` (on `:return`) or
  `:error`, the class and message of what the call threw (on `:throw`); `:id`
  pairs a call with its return or throw.  Events are only produced when the output mode
  includes `:listeners` (see `set-output-mode!`)."
  [f]
  (swap! listeners conj f)
  f)

(defn remove-trace-listener
  "Remove a previously registered trace listener F."
  [f]
  (swap! listeners disj f)
  nil)

(defn set-output-mode!
  "Set how trace output is delivered.
  MODE is `:repl` (println to `*out*`, the default), `:listeners` (structured
  events to the fns registered with `add-trace-listener`) or `:both`."
  [mode]
  {:pre [(contains? #{:repl :listeners :both} mode)]}
  (reset! output-mode mode))

(defn current-output-mode
  "Return the current trace output mode."
  []
  @output-mode)

(defn traceable?
  "Return true if the given var can be traced."
  [v]
  (let [v (resolve-var v)]
    (and (ifn? @v) (not (:macro (meta v))))))

(defn traced?
  "Return true if the given var is currently traced."
  [v]
  (let [v (resolve-var v)]
    (contains? (meta v) ::traced)))

(defn trace-var*
  "If the specified Var holds an IFn and is not marked as a macro, its
  contents is replaced with a version wrapped in a tracing call;
  otherwise nothing happens. Can be undone with `untrace-var*`."
  [v]
  (let [v (resolve-var v)
        ns (.ns v)
        s  (.sym v)]
    (when (and (traceable? v) (not (traced? v)))
      (let [f @v
            vname (symbol (str ns "/" s))
            tooling? (tooling-ns? ns)]
        (swap! traced-vars conj v)
        (alter-var-root v #(fn tracing-wrapper [& args]
                             (trace-fn-call vname tooling? % args)))
        (alter-meta! v assoc ::traced f)
        v))))

(defn untrace-var*
  "Reverses the effect of `trace-var*` for the given Var, replacing the traced
  function with the original, untraced version. No-op for non-traced Vars."
  [v]
  (let [v (resolve-var v)
        f (::traced (meta v))]
    (when f
      (alter-var-root v (constantly (::traced (meta v))))
      (alter-meta! v dissoc ::traced)
      (swap! traced-vars disj v)
      v)))

(defn trace-ns*
  "Trace all Vars in the given namespace. Can be undone with `untrace-ns*`. `ns`
  should be a namespace object or a symbol.

  No-op for clojure.core and orchard.trace."
  [ns]
  (let [ns (the-ns ns)]
    (when-not ('#{clojure.core orchard.trace} (.name ns))
      (->> (ns-interns ns)
           vals
           #_(filter (comp fn? var-get)) ;; BB-PATCH user/*input* is interned as a non-var
(filter #(and (var? %) (fn? (var-get %))))
           (run! trace-var*))
      (swap! traced-nses conj ns))))

(defn untrace-ns*
  "Untrace all Vars in the given namespace."
  [ns]
  (let [ns (the-ns ns)]
    (->> (ns-interns ns)
         vals
         #_(filter (comp fn? var-get)) ;; BB-PATCH user/*input* is interned as a non-var
(filter #(and (var? %) (fn? (var-get %))))
         (run! untrace-var*))
    (swap! traced-nses disj ns)))

(defn untrace-all
  "Reverses the effect of tracing for all already traced vars and namespaces."
  []
  (run! untrace-ns* @traced-nses)
  (run! untrace-var* @traced-vars))
