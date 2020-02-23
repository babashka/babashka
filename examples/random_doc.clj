#!/usr/bin/env bb

(require '[clojure.repl])

(defmacro random-doc []
  (let [sym (-> (ns-publics 'clojure.core) keys rand-nth)]
    (if (:doc (meta (resolve sym)))
      `(clojure.repl/doc ~sym)
      `(random-doc))))

(random-doc)
