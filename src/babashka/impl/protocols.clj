(ns babashka.impl.protocols
  (:require [clojure.datafy :as d]
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

;;;; sci namespace
(def protocols-ns (sci/create-ns 'clojure.core.protocols nil))

(def protocols-namespace
  {'Datafiable (sci/new-var 'clojure.core.protocols/Datafiable {:methods #{'datafy}
                                                                :ns protocols-ns} {:ns protocols-ns})
   'datafy (copy-var datafy protocols-ns)
   'Navigable (sci/new-var 'clojure.core.protocols/Navigable {:methods #{'nav}
                                                              :ns protocols-ns} {:ns protocols-ns})
   'nav (copy-var nav protocols-ns)
   'IKVReduce (copy-var datafy protocols-ns)})
