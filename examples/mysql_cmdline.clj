#!/usr/bin/env bb

;; Simple example of accessing mysql's data via the command line, then turnning around and dumping that data as a table.

;; (map2arg-- mdb "-h" :host) => "-hlocalhost"
(defn map2arg-
  "Create mysql command line argument from connection map"
  [mdb arg key]
  (str arg (get mdb key)))

(defn make-mysql-command-
  "Create mysql command line using connection map and statement"
  [mdb statement]
  ["mysql" (map2arg- mdb "-h" :host) (map2arg- mdb "-u" :user) (map2arg- mdb "-p" :password) (:dbname mdb) "--column-names" "-e" statement ])

(defn query
  "Executes a query agatinst the command line mysql. Return is a vector of maps with populated with each row."
  [ mdb statement ]
  (let [
        mysql-command (make-mysql-command- mdb statement)
        table-as-str (:out (apply shell/sh mysql-command))
        table-as-lines (str/split-lines table-as-str)
        table-headers (str/split (first table-as-lines) #"\t")
        table-as-maps (map #(zipmap table-headers (str/split %1 #"\t")) (rest table-as-lines))
        ]
    table-as-maps
    ))

;; Typical connection specifier
(def mdb {:dbtype "mysql" :dbname "corp" :host "localhost" :user "dba" :password "dba"})

;; extracted rows
(def rows (query mdb "select TABLE_NAME, ENGINE from information_schema.tables limit 3"))

;; display the data!
(clojure.pprint/print-table rows)


;; Tells emacs to jump into clojure mode.
;; Local Variables:
;; mode: clojure
;; End:


