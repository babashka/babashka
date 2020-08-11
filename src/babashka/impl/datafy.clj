(ns babashka.impl.datafy
  {:no-doc true}
  (:refer-clojure :exclude [create-ns])
  (:require [babashka.impl.common :refer [ctx]]
            [babashka.impl.protocols :as protocols]
            [clojure.core.protocols :as p]
            [clojure.datafy :as datafy]
            [clojure.reflect]
            [sci.core :refer [create-ns copy-var]]
            [sci.impl.namespaces :refer [sci-ns-name sci-ns-publics sci-ns-imports sci-ns-interns]]
            [sci.impl.vars])
  (:import [sci.impl.vars SciNamespace]))

(defn- sortmap [m]
  (into (sorted-map) m))

;; Overrides for what is defined in clojure.datafy
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

(def datafy-ns (create-ns 'clojure.data nil))

(def datafy-namespace
  {'datafy (copy-var protocols/datafy datafy-ns)
   'nav (copy-var datafy/nav datafy-ns)})
