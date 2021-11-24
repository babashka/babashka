(ns babashka.impl.protocols
  (:require [babashka.impl.common :refer [ctx]]
            [babashka.impl.protocols :as protocols]
            [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            ;; ensure datafy is loaded, we're going to override its
            ;; clojure.lang.Namespace implementation for datafy
            [clojure.reflect]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.namespaces :refer [sci-ns-name sci-ns-publics sci-ns-imports sci-ns-interns]]
            [sci.impl.types :as types]
            [sci.impl.vars])
  (:import [sci.impl.vars SciNamespace]))

;;;; datafy

(defmulti datafy types/type-impl)

(defmethod datafy :sci.impl.protocols/reified [x]
  (let [methods (types/getMethods x)]
    ((get methods 'datafy) x)))

(defmethod datafy :default [x]
  ;; note: Clojure itself will handle checking metadata for impls
  (d/datafy x))

;;;; nav
(defmulti nav types/type-impl)

(defmethod nav :sci.impl.protocols/reified [coll k v]
  (let [methods (types/getMethods coll)]
    ((get methods 'nav) coll k v)))

(defmethod nav :default [coll k v]
  ;; note: Clojure itself will handle checking metadata for impls
  (d/nav coll k v))

;;;; IKVreduce

;; (defmulti kv-reduce types/type-impl)

;; (defmethod kv-reduce :sci.impl.protocols/reified [amap f init]
;;   (let [methods (types/getMethods amap)]
;;     ((get methods 'kv-reduce) amap f init)))

;; (defmethod kv-reduce :default [amap f init]
;;   (p/kv-reduce amap f init))

;;;; sci namespace

;; Overrides for what is defined in clojure.datafy

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

(def protocols-ns (sci/create-ns 'clojure.core.protocols nil))

(def protocols-namespace
  {;; Datafiable
   'Datafiable (sci/new-var 'clojure.core.protocols/Datafiable {:methods #{'datafy}
                                                                :protocol p/Datafiable
                                                                :ns protocols-ns}
                            {:ns protocols-ns})
   'datafy (copy-var datafy protocols-ns)

   ;; Navigable
   'Navigable (sci/new-var 'clojure.core.protocols/Navigable {:methods #{'nav}
                                                              :protocol p/Navigable
                                                              :ns protocols-ns}
                           {:ns protocols-ns})
   'nav (copy-var nav protocols-ns)

   ;; IKVReduce only added for satisies? check for now. We can implement
   ;; kv-reduce in the future, but this needs patching some functions like
   ;; update-vals, etc.
   'IKVReduce (sci/new-var 'clojure.core.protocols/IKVReduce {:protocol p/IKVReduce
                                                              ;; :methods #{'kv-reduce}
                                                              :ns protocols-ns}
                           {:ns protocols-ns})
   ;; 'kv-reduce (copy-var kv-reduce protocols-ns)
   }
  )
