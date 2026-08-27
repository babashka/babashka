(ns hooks.babashka.ffi
  (:require [clj-kondo.hooks-api :as api]))

(defn defcfn
  "Lints both defcfn forms. The plain form is a def. The wrapper form
  becomes a def of a let that binds the raw name over a fn, so the body is
  linted with the raw binding in scope."
  [{:keys [node]}]
  (let [[nm & args] (rest (:children node))
        anchor (first (keep-indexed (fn [i n] (when (api/vector-node? n) i)) args))
        wrapper (when (and anchor (> (count args) (+ anchor 2)))
                  (drop (+ anchor 2) args))]
    {:node
     (if (seq wrapper)
       (api/list-node
        [(api/token-node 'def) nm
         (api/list-node
          [(api/token-node 'clojure.core/let)
           (api/vector-node
            [(first wrapper)
             (api/list-node
              [(api/token-node 'clojure.core/fn)
               (api/vector-node [(api/token-node '&) (api/token-node '_)])])])
           (api/list-node
            (list* (api/token-node 'clojure.core/fn) (rest wrapper)))])])
       (api/list-node
        [(api/token-node 'def) nm
         (api/list-node (list* (api/token-node 'do) args))]))}))
