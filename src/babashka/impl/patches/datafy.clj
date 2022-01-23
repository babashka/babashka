(ns babashka.impl.patches.datafy
  (:require [babashka.impl.common :refer [ctx]]
            [clojure.core.protocols :as p]
            [clojure.datafy]
            ;; ensure datafy is loaded, we're going to override its
            ;; clojure.lang.Namespace implementation for datafy
            [clojure.reflect]
            [sci.impl.namespaces :refer [sci-ns-name sci-ns-publics sci-ns-imports sci-ns-interns]]
            [sci.impl.vars])
  (:import [sci.impl.vars SciNamespace]))

(defn- sortmap [m]
  (into (sorted-map) m))

(extend-protocol p/Datafiable
  clojure.lang.Namespace
  (datafy [n]
    ;; Override this with the default Object implementation. It bloats bb with 30mb and memory usage of GraalVM will peak!
    #_(with-meta {:name (.getName n)
                  :publics (-> n ns-publics sortmap)
                  :imports (-> n ns-imports sortmap)
                  :interns (-> n ns-interns sortmap)}
        (meta n))
    n)
  java.lang.Class
  (datafy [c]
    ;; Statically use clojure.reflect instead of leaning on requiring-resolve
    (let [{:keys [members] :as ret} (clojure.reflect/reflect c)]
      (assoc ret :name (-> c .getName symbol) :members (->> members (group-by :name) sortmap)))))

(extend-protocol p/Datafiable
  SciNamespace
  (datafy [n]
    (with-meta {:name (sci-ns-name n)
                :publics (->> n (sci-ns-publics @ctx) sortmap)
                :imports (->> n (sci-ns-imports @ctx) sortmap)
                :interns (->> n (sci-ns-interns @ctx) sortmap)}
      (meta n))))
