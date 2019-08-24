#!/usr/bin/env bb --time -o -f

;; Usage: sqlite.clj <paths>.

;; Lints paths with clj-kondo, stores var information in sqlite database,
;; executes query and prints output.

;; Example:
;; $ examples/sqlite.clj sci/src
;; 'ns','name','filename','row','col'
;; 'sci.impl.main','-main','sci/src/sci/impl/main.cljc',10,1

(def db "/tmp/analysis.db")

(defn query [& args]
  (apply shell/sh "sqlite3" "-quote" "-header" "-separator" " | " db args))

(defn create-db! []
  (when (not (.exists (io/file db)))
    (query "create table vars (
             ns text, name text, filename text
             , row int, col int )")
    (query "create table var_usages (
             'from' text, 'to' text, name text, filename text
              , row int, col int )")))

(defn text-val [s]
  (format "\"%s\"" s))

(defn int-val [i]
  (format "%s" i))

(defn make-row [& xs]
  (format "(%s)" (str/join ", " xs)))

(defn var-definition-row [{:keys [:ns :name :filename :row :col]}]
  (make-row (text-val ns) (text-val name)
            (text-val filename) (int-val row) (int-val col)))

(defn insert-vars! [var-definitions]
  (let [rows (str/join "," (mapv var-definition-row var-definitions))
        q (str/join " " ["insert into vars (ns, name, filename, row, col) values"
                         rows])
        {:keys [:exit :err]}
        (query q)]
    (when (not (zero? exit))
      (println "error inserting var!" err)
      (System/exit exit))))

(defn var-usage-row [{:keys [:from :to :name :filename :row :col]}]
  (make-row (text-val from) (text-val to) (text-val name)
            (text-val filename) (int-val row) (int-val col)))

(defn insert-var-usages! [var-usages]
  (let [rows (str/join "," (map var-usage-row var-usages))
        q (str/join " " ["insert into var_usages ('from', 'to', name, filename, row, col) values"
                         rows])
        {:keys [:exit :err]}
        (query q)]
    (when (not (zero? exit))
      (println "error inserting var usage!" err)
      (System/exit exit))))

(defn analysis->db [paths]
  (let [out (:out (apply shell/sh "clj-kondo"
                         "--config" "{:output {:analysis true :format :edn}}"
                         "--lint" paths))
        analysis (:analysis (edn/read-string out))
        {:keys [:var-definitions :var-usages]} analysis]
    (run! insert-vars! (partition-all 500 var-definitions))
    (run! insert-var-usages! (partition-all 500 var-usages))))

(.delete (io/file db))
(create-db!)
(analysis->db *command-line-args*)

(defn unused-vars []
  (-> (query (str/join " " ["select distinct * from vars v"
                            "where (v.ns, v.name) not in"
                            "(select vu.'to', vu.name from var_usages vu)"]))
      :out))

(unused-vars)
