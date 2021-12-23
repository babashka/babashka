(ns babashka.impl.jdbc
  {:no-doc true}
  (:require [next.jdbc :as njdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [sci.impl.namespaces :refer [copy-var macrofy]]
            [sci.impl.vars :as vars]))

(def next-ns (vars/->SciNamespace 'next.jdbc nil))

(defn with-transaction
  "Given a transactable object, gets a connection and binds it to `sym`,
  then executes the `body` in that context, committing any changes if the body
  completes successfully, otherwise rolling back any changes made.
  The options map supports:
  * `:isolation` -- `:none`, `:read-committed`, `:read-uncommitted`,
      `:repeatable-read`, `:serializable`,
  * `:read-only` -- `true` / `false`,
  * `:rollback-only` -- `true` / `false`."
  [_ _ [sym transactable opts] & body]
  (let [con (vary-meta sym assoc :tag 'java.sql.Connection)]
    `(next.jdbc/transact ~transactable (^{:once true} fn* [~con] ~@body) ~(or opts {}))))

(def njdbc-namespace
  {'get-datasource (copy-var njdbc/get-datasource next-ns)
   'execute! (copy-var njdbc/execute! next-ns)
   'execute-one! (copy-var njdbc/execute-one! next-ns)
   'get-connection (copy-var njdbc/get-connection next-ns)
   'plan (copy-var njdbc/plan next-ns)
   'prepare (copy-var njdbc/prepare next-ns)
   'transact (copy-var njdbc/transact next-ns)
   'with-transaction (macrofy 'with-transaction with-transaction next-ns)})

(def sns (vars/->SciNamespace 'next.jdbc.sql nil))

(def next-sql-namespace
  {'insert-multi! (copy-var sql/insert-multi! sns)})

(def rsns (vars/->SciNamespace 'next.jdbc.result-set nil))

(def result-set-namespace
  {'as-maps (copy-var rs/as-maps rsns)
   'as-unqualified-maps (copy-var rs/as-unqualified-maps rsns)
   'as-modified-maps (copy-var rs/as-modified-maps rsns)
   'as-unqualified-modified-maps (copy-var rs/as-unqualified-modified-maps rsns)
   'as-lower-maps (copy-var rs/as-lower-maps rsns)
   'as-unqualified-lower-maps (copy-var rs/as-unqualified-lower-maps rsns)
   'as-maps-adapter (copy-var rs/as-maps-adapter rsns)})
