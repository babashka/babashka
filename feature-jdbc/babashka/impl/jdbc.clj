(ns babashka.impl.jdbc
  {:no-doc true}
  (:require
   [next.jdbc :as njdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.sql :as sql]
   [sci.core :as sci]))

(def next-ns (sci/create-ns 'next.jdbc nil))

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
    `(njdbc/transact ~transactable (^{:once true} fn* [~con] ~@body) ~(or opts {}))))

(def njdbc-namespace
  {'get-datasource (sci/copy-var njdbc/get-datasource next-ns)
   'execute! (sci/copy-var njdbc/execute! next-ns)
   'execute-one! (sci/copy-var njdbc/execute-one! next-ns)
   'get-connection (sci/copy-var njdbc/get-connection next-ns)
   'plan (sci/copy-var njdbc/plan next-ns)
   'prepare (sci/copy-var njdbc/prepare next-ns)
   'transact (sci/copy-var njdbc/transact next-ns)
   'with-transaction (sci/copy-var with-transaction next-ns)})

(def sns (sci/create-ns 'next.jdbc.sql nil))

(def next-sql-namespace
  {'insert-multi! (sci/copy-var sql/insert-multi! sns)})

(def rsns (sci/create-ns 'next.jdbc.result-set nil))

(def result-set-namespace
  {'as-maps (sci/copy-var rs/as-maps rsns)
   'as-unqualified-maps (sci/copy-var rs/as-unqualified-maps rsns)
   'as-modified-maps (sci/copy-var rs/as-modified-maps rsns)
   'as-unqualified-modified-maps (sci/copy-var rs/as-unqualified-modified-maps rsns)
   'as-lower-maps (sci/copy-var rs/as-lower-maps rsns)
   'as-unqualified-lower-maps (sci/copy-var rs/as-unqualified-lower-maps rsns)
   'as-maps-adapter (sci/copy-var rs/as-maps-adapter rsns)})
