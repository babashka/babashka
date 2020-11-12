(ns babashka.impl.jdbc
  {:no-doc true}
  (:require [next.jdbc :as njdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.plan :as plan]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

;;
;; next.jdbc
;;
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
   'with-options (copy-var njdbc/with-options next-ns)
   'with-transaction (with-meta with-transaction
                       {:sci/macro true})})

;;
;; next.jdbc.sql
;;
(def sns (vars/->SciNamespace 'next.jdbc.sql nil))

(def next-sql-namespace
  {'delete! (copy-var sql/delete! sns)
   'find-by-keys (copy-var sql/find-by-keys sns)
   'get-by-id (copy-var sql/get-by-id sns)
   'insert! (copy-var sql/insert! sns)
   'insert-multi! (copy-var sql/insert-multi! sns)
   'query (copy-var sql/query sns)
   'update! (copy-var sql/update! sns)})

;;
;; next.jdbc.plan
;;
(def plns (vars/->SciNamespace 'next.jdbc.plan nil))

(def next-plan-namespace
  {'select! (copy-var plan/select! plns)
   'select-one! (copy-var plan/select-one! plns)})

;;
;; next.jdbc.prepare
;;
;(def prpns (vars/->SciNamespace 'next.jdbc.prepare nil))
;
;(def next-prepare-namespace ; with these I got OutOfMemory after 40 min of compilation
;  {'execute-batch! (copy-var prepare/execute-batch! prpns)
;   'set-parameters (copy-var prepare/set-parameters prpns)
;   'statement (copy-var prepare/statement prpns)})
