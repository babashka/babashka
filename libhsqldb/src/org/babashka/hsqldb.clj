(ns org.babashka.hsqldb
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc])
  (:gen-class))

(def lookup
  {'hsqldb.jdbc/execute! jdbc/execute!})

(defn -main [& _args]
  (loop []
    (let [message (edn/read {:eof ::EOF} *in*)]
      (when-not (identical? ::EOF message)
        (let [op (:op message)]
          (case op
            :invoke (let [var (:var message)
                          args (:args message)]
                      (prn {:value (apply (lookup var) args)})
                      (recur))
            :namespaces (prn {:namespaces {'hsqldb.jdbc {}}})))))))
