(ns org.babashka.lib-hsqldb
  (:require [next.jdbc :as jdbc]
            [sci.core :as sci])
  (:gen-class
   :methods [^{:static true} [evalString [String] String]]))

(defn -evalString [s]
  (sci/binding [sci/out *out*] ;; this enables println etc.
    (str (sci/eval-string
          s
          {:namespaces {'next.jdbc {'execute! jdbc/execute!}}}))))
