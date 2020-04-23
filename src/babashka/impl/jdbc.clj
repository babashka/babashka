(ns babashka.impl.jdbc
  {:no-doc true}
  (:require [next.jdbc :as njdbc]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def next-ns (vars/->SciNamespace 'next.jdbc nil))

(def njdbc-namespace
  {'get-datasource (copy-var njdbc/get-datasource next-ns)
   'execute! (copy-var njdbc/execute! next-ns)
   'execute-one! (copy-var njdbc/execute-one! next-ns)
   'get-connection (copy-var njdbc/get-connection next-ns)
   'plan (copy-var njdbc/plan next-ns)
   'prepare (copy-var njdbc/prepare next-ns)
   'transact (copy-var njdbc/transact next-ns)})
