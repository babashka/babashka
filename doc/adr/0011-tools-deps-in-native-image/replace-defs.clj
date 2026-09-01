#!/usr/bin/env bb
;; For each (def name ...) in target/tools-deps/precise-defs.clj, replace the def of
;; the same name in src/babashka/impl/classes.clj.
(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.parser :as p]
         '[rewrite-clj.node :as n])

(def f "src/babashka/impl/classes.clj")

(defn def-name [loc]
  (when (= :list (z/tag loc))
    (let [d (z/down loc)]
      (when (= 'def (z/sexpr d))
        (z/sexpr (z/right d))))))

(let [new-forms (->> (n/children (p/parse-string-all (slurp "target/tools-deps/precise-defs.clj")))
                     (filter #(= :list (n/tag %))))
      zloc (reduce (fn [zloc form]
                     (let [nm (second (n/sexpr form))
                           target (z/find (z/of-node (z/root zloc)) z/next #(= nm (def-name %)))]
                       (assert target (str "no def " nm))
                       (z/replace target form)))
                   (z/of-file f)
                   new-forms)]
  (spit f (z/root-string zloc))
  (println "replaced" (count new-forms) "defs"))
