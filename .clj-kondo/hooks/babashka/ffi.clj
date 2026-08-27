(ns hooks.babashka.ffi
  (:require [clj-kondo.hooks-api :as api]))

(defn- layout-vector?
  "A layout such as [:struct ...] is a return type, never argtypes."
  [n]
  (and (api/vector-node? n)
       (= :struct (some-> n :children first api/sexpr))))

(defn defcfn
  "Lints both defcfn forms as a defn, so the docstring, the attribute map
  and the arglists reach the analysis. The wrapper body sees the raw
  binding through a let."
  [{:keys [node]}]
  (let [[nm & args] (rest (:children node))
        anchor (first (keep-indexed (fn [i n]
                                      (when (and (api/vector-node? n)
                                                 (not (layout-vector? n)))
                                        i))
                                    args))
        prefix (if (and anchor (pos? anchor)) (take (dec anchor) args) [])
        docstring (first (filter api/string-node? prefix))
        attr-map (first (filter api/map-node? prefix))
        argtypes (when anchor (nth args anchor))
        wrapper (when (and anchor (> (count args) (+ anchor 2)))
                  (drop (+ anchor 2) args))
        head (cond-> [(api/token-node 'clojure.core/defn) nm]
               docstring (conj docstring)
               attr-map (conj attr-map))]
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
         (api/list-node (into head (map bind arities))))

       ;; plain form with literal argtypes: a synthetic arglist of the same
       ;; arity, so calls are checked
       anchor
       (let [params (api/vector-node
                     (map-indexed (fn [i _] (api/token-node (symbol (str "_arg" i))))
                                  (:children argtypes)))]
         (api/list-node
          (conj head params
                (api/list-node
                 (list* (api/token-node 'do)
                        (concat [argtypes]
                                (drop (inc anchor) args)
                                [(api/token-node nil)]))))))

       ;; plain form without a literal argtypes vector: a def
       :else
       (api/list-node
        [(api/token-node 'def) nm
         (api/list-node (list* (api/token-node 'do) args))]))}))
