(ns babashka.impl.protocols
  (:require [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.types :as types]))

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
(def protocols-ns (sci/create-ns 'clojure.core.protocols nil))

(defn trim-protocol
  [protocol]
  (select-keys protocol [:on :on-interface :impls]))

(def protocols-namespace
  {;; Datafiable
   'Datafiable (sci/new-var 'clojure.core.protocols/Datafiable {:methods #{'datafy}
                                                                ;; :protocol p/Datafiable
                                                                :ns protocols-ns}
                            {:ns protocols-ns})
   'datafy (copy-var datafy protocols-ns)

   ;; Navigable
   'Navigable (sci/new-var 'clojure.core.protocols/Navigable {:methods #{'nav}
                                                              ;; :protocol p/Navigable
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
