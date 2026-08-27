(ns hooks.babashka.ffi
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private layout-kinds
  "Mirrors babashka.ffi/layout-kinds: extend both."
  #{:struct})

(defn- layout-vector?
  [n]
  (and (api/vector-node? n)
       (contains? layout-kinds (some-> n :children first api/sexpr))))

(defn defcfn
  "Rewrites both defcfn forms to a defn, so the docstring, the attribute
  map, the arglists, the wrapper body and the C symbol, argtypes and return
  type expressions all reach the analysis."
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
        ;; the C symbol, argtypes and return type are linted as well
        extras (remove nil? [sym-node argtypes rettype])
        wrap-do (fn [n]
                  (if (seq extras)
                    (api/list-node (concat [(api/token-node 'do)] extras [n]))
                    n))]
    {:node
     (cond
       (seq wrapper)
       ;; each arity body sees the raw binding
       (let [raw (first wrapper)
             tail (rest wrapper)
             arities (if (api/vector-node? (first tail))
                       [tail]
                       (map :children tail))
             bind (fn [[params & body]]
                    (api/list-node
                     [params
                      (api/list-node
                       [(api/token-node 'clojure.core/let)
                        (api/vector-node
                         [raw (api/list-node
                               [(api/token-node 'clojure.core/fn)
                                (api/vector-node [(api/token-node '&)
                                                  (api/token-node '_)])])])
                        (api/list-node
                         (list* (api/token-node 'do) body))])]))]
         (wrap-do (api/list-node (into head (map bind arities)))))

       ;; plain form with literal argtypes: an arglist of the same arity,
       ;; variadic when the types end in :&
       anchor
       (let [types (:children argtypes)
             variadic? (= :& (api/sexpr (last types)))
             fixed (if variadic? (butlast types) types)
             params (cond-> (mapv (fn [i] (api/token-node (symbol (str "_arg" i))))
                                  (range (count fixed)))
                      variadic? (conj (api/token-node '&)
                                      (api/token-node '_rest)))]
         (wrap-do (api/list-node (conj head
                                       (api/vector-node params)
                                       (api/token-node nil)))))

       ;; plain form without a literal argtypes vector: any arity fits
       :else
       (api/list-node
        (conj head
              (api/vector-node [(api/token-node '&) (api/token-node '_args)])
              (api/list-node (list* (api/token-node 'do) (take-last 3 args))))))}))
