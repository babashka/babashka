(ns hooks.babashka.ffi
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private layout-kinds
  ;; Keep in sync with babashka.ffi/layout-kinds.
  #{:struct})

(defn- layout-vector?
  [n]
  (and (api/vector-node? n)
       (contains? layout-kinds (some-> n :children first api/sexpr))))

(defn defcfn
  "Rewrites defcfn forms for linting."
  [{:keys [node]}]
  (let [[nm & args] (rest (:children node))
        anchor (first (keep-indexed (fn [i n]
                                      (when (and (api/vector-node? n)
                                                 (not (layout-vector? n)))
                                        i))
                                    args))
        anchored? (and anchor (pos? anchor))
        prefix (if anchored? (take (dec anchor) args) (drop-last 3 args))
        docstring (first (filter api/string-node? prefix))
        attr-map (first (filter api/map-node? prefix))
        sym-node (when anchored? (nth args (dec anchor)))
        argtypes (when anchor (nth args anchor))
        rettype (when (and anchor (> (count args) (inc anchor)))
                  (nth args (inc anchor)))
        wrapper (when (and anchor (> (count args) (+ anchor 2)))
                  (drop (+ anchor 2) args))
        head (cond-> [(api/token-node 'clojure.core/defn) nm]
               docstring (conj docstring)
               attr-map (conj attr-map))
        ;; Lint signature expressions.
        extras (remove nil? [sym-node argtypes rettype])
        wrap-do (fn [n]
                  (if (seq extras)
                    (api/list-node (concat [(api/token-node 'do)] extras [n]))
                    n))
        ;; The C return value has no static type.
        opaque-value (fn []
                       (api/list-node
                        [(api/token-node 'clojure.core/deref)
                         (api/list-node [(api/token-node 'clojure.core/atom)
                                         (api/token-node nil)])]))
        ;; Match fixed and variadic C arities.
        arg-params (fn []
                     (let [types (:children argtypes)
                           variadic? (= :& (some-> (last types) api/sexpr))
                           fixed (if variadic? (butlast types) types)]
                       (api/vector-node
                        (cond-> (mapv (fn [i] (api/token-node (symbol (str "_arg" i))))
                                      (range (count fixed)))
                          variadic? (conj (api/token-node '&)
                                          (api/token-node '_rest))))))]
    {:node
     (cond
       (seq wrapper)
       ;; Use the same raw-binding scope as the macro.
       (let [raw (first wrapper)
             tail (rest wrapper)
             arities (if (api/vector-node? (first tail))
                       [tail]
                       (map :children tail))
             arity-node (fn [children] (api/list-node children))]
         (wrap-do
          (api/list-node
           [(api/token-node 'clojure.core/let)
            (api/vector-node
             [raw (api/list-node
                   [(api/token-node 'clojure.core/fn)
                    (arg-params)
                    (opaque-value)])])
            (api/list-node (into head (map arity-node arities)))])))

       ;; Model literal argtypes as a fixed or variadic arity.
       anchor
       (wrap-do (api/list-node (conj head
                                     (arg-params)
                                     (opaque-value))))

       ;; Dynamic argtypes have no static arity.
       :else
       (api/list-node
        (conj head
              (api/vector-node [(api/token-node '&) (api/token-node '_args)])
              (api/list-node (list* (api/token-node 'do) (take-last 3 args))))))}))
