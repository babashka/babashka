(ns babashka.impl.protocols
  (:require [babashka.impl.protocols :as protocols]
            [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            ;; ensure datafy is loaded, we're going to override its
            ;; clojure.lang.Namespace implementation for datafy
            [clojure.reflect]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.types :as types]
            [sci.impl.vars]))

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
