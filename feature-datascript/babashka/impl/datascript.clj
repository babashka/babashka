(ns babashka.impl.datascript
  {:no-doc true}
  (:require [datascript.core :as d]
            [datascript.db :as db]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def datascript-ns (vars/->SciNamespace 'datascript.core nil))
(def datascript-db-ns (vars/->SciNamespace 'datascript.db nil))

(def datascript-namespace
  {'create-conn (copy-var d/create-conn datascript-ns)
   'transact!   (copy-var d/transact! datascript-ns)
   'q           (copy-var d/q datascript-ns)
   'empty-db    (copy-var d/empty-db datascript-ns)
   'db-with     (copy-var d/db-with datascript-ns)
   'filter      (copy-var d/filter datascript-ns)
   'init-db     (copy-var d/init-db datascript-ns)
   'datom       (copy-var d/datom datascript-ns)
   'pull        (copy-var d/pull datascript-ns)
   'pull-many   (copy-var d/pull-many datascript-ns)})

(def datascript-db-namespace
  {'db-from-reader    (copy-var db/db-from-reader datascript-db-ns)
   'datom-from-reader (copy-var db/datom-from-reader datascript-db-ns)
   'datom-added       (copy-var db/datom-added datascript-db-ns)
   'datom-tx          (copy-var db/datom-tx datascript-db-ns)})
