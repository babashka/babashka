(ns babashka.impl.protocols
  (:require [clojure.datafy :as d]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.types :as types]))

(defmulti datafy types/type-impl)

(defmethod datafy :sci.impl.protocols/reified [x]
  (let [methods (types/getMethods x)]
    ((get methods 'datafy) x)))

(defmethod datafy :default [x]
  (d/datafy x))

;; TODO: Navigable

(def protocols-ns (sci/create-ns 'clojure.core.protocols nil))

(def protocols-namespace
  {'Datafiable (sci/new-var 'clojure.core.protocols/Datafiable {:methods #{'datafy}
                                                                :ns protocols-ns})
   'datafy (copy-var datafy protocols-ns)})
