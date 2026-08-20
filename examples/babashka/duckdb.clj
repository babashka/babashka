(ns babashka.duckdb
  "DuckDB over babashka.ffi against libduckdb. Same surface as
  babashka.sqlite, so honeysql-formatted vectors work as-is:

      (require '[babashka.duckdb :as duck])
      (duck/query \"/tmp/analytics.db\"
        [\"select * from 'events.csv' where user = ?\" \"michiel\"])

  A string db argument opens and closes the database around the call; nil
  as path opens an in-memory database via open. For many operations hold a
  connection:

      (duck/with-db [db nil]   ; in-memory
        (duck/execute! db [\"create table t as select * from 'data.parquet'\"])
        (duck/query db \"select count(*) c from t\"))

  Rows come back as maps with keywordized column names. Integer columns
  (including booleans, as 0/1) come back as longs, FLOAT/DOUBLE as doubles,
  everything else (VARCHAR, DATE, TIMESTAMP, ...) as strings."
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(ffi/load-system-library "duckdb")

(defcfn ^:private c-library-version "duckdb_library_version" [] :string)
(defcfn ^:private c-open "duckdb_open" [:string :pointer] :int)
(defcfn ^:private c-close "duckdb_close" [:pointer] :void)
(defcfn ^:private c-connect "duckdb_connect" [:pointer :pointer] :int)
(defcfn ^:private c-disconnect "duckdb_disconnect" [:pointer] :void)
(defcfn ^:private c-query "duckdb_query" [:pointer :string :pointer] :int)
(defcfn ^:private c-destroy-result "duckdb_destroy_result" [:pointer] :void)
(defcfn ^:private c-result-error "duckdb_result_error" [:pointer] :string)
(defcfn ^:private c-column-count "duckdb_column_count" [:pointer] :uint64)
(defcfn ^:private c-row-count "duckdb_row_count" [:pointer] :uint64)
(defcfn ^:private c-rows-changed "duckdb_rows_changed" [:pointer] :uint64)
(defcfn ^:private c-column-name "duckdb_column_name" [:pointer :uint64] :string)
(defcfn ^:private c-column-type "duckdb_column_type" [:pointer :uint64] :int)
(defcfn ^:private c-value-is-null "duckdb_value_is_null" [:pointer :uint64 :uint64] :uint8)
(defcfn ^:private c-value-int64 "duckdb_value_int64" [:pointer :uint64 :uint64] :int64)
(defcfn ^:private c-value-double "duckdb_value_double" [:pointer :uint64 :uint64] :double)
(defcfn ^:private c-value-varchar "duckdb_value_varchar" [:pointer :uint64 :uint64] :pointer)
(defcfn ^:private c-duckdb-free "duckdb_free" [:pointer] :void)
(defcfn ^:private c-prepare "duckdb_prepare" [:pointer :string :pointer] :int)
(defcfn ^:private c-destroy-prepare "duckdb_destroy_prepare" [:pointer] :void)
(defcfn ^:private c-prepare-error "duckdb_prepare_error" [:pointer] :string)
(defcfn ^:private c-execute-prepared "duckdb_execute_prepared" [:pointer :pointer] :int)
(defcfn ^:private c-bind-int64 "duckdb_bind_int64" [:pointer :uint64 :int64] :int)
(defcfn ^:private c-bind-double "duckdb_bind_double" [:pointer :uint64 :double] :int)
(defcfn ^:private c-bind-varchar "duckdb_bind_varchar" [:pointer :uint64 :string] :int)
(defcfn ^:private c-bind-null "duckdb_bind_null" [:pointer :uint64] :int)

(defn version
  "The DuckDB library version string."
  []
  (c-library-version))

;; duckdb_result is a 48-byte out-param struct; allocate with headroom
(def ^:private result-size 64)

(defn open
  "Opens the database at path, nil for in-memory. Returns a connection for
  use with execute!, query and close!."
  [path]
  (let [pdb (ffi/alloc (ffi/sizeof :pointer))
        pconn (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (when-not (zero? (c-open path pdb))
        (throw (ex-info (str "duckdb: cannot open " path) {})))
      (let [db (ffi/read pdb :pointer)]
        (when-not (zero? (c-connect db pconn))
          (c-close pdb)
          (throw (ex-info "duckdb: cannot connect" {})))
        {:db pdb :conn (ffi/read pconn :pointer)})
      (finally (ffi/free pconn)))))

(defn close!
  "Closes a connection returned by open."
  [{:keys [db conn]}]
  (let [pconn (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (ffi/write pconn :pointer 0 conn)
      (c-disconnect pconn)
      (c-close db)
      (finally
        (ffi/free pconn)
        (ffi/free db))))
  nil)

(defmacro with-db
  "(with-db [db \"/tmp/analytics.db\"] ...) - opens the database (nil path =
  in-memory), binds the connection, closes it after the body."
  [[sym path] & body]
  `(let [~sym (open ~path)]
     (try ~@body
          (finally (close! ~sym)))))

(defn- varchar-at [res col row]
  (let [p (c-value-varchar res col row)]
    (when-not (ffi/null?* p)
      (let [s (ffi/ptr->string p)]
        (c-duckdb-free p)
        s))))

(defn- value-at [res col row type-id]
  (when (zero? (c-value-is-null res col row))
    (cond
      ;; BOOLEAN..UBIGINT
      (<= 1 type-id 9) (c-value-int64 res col row)
      ;; FLOAT, DOUBLE
      (<= 10 type-id 11) (c-value-double res col row)
      :else (varchar-at res col row))))

(defn- read-rows [res]
  (let [ncol (c-column-count res)
        cols (mapv (fn [c] {:name (keyword (c-column-name res c))
                            :type (c-column-type res c)})
                   (range ncol))
        nrow (c-row-count res)]
    (mapv (fn [r]
            (into {} (map-indexed
                      (fn [c {:keys [name type]}]
                        [name (value-at res c r type)])
                      cols)))
          (range nrow))))

(defn- run* [conn q collect-rows?]
  (let [[sql & params] (if (string? q) [q] q)
        res (ffi/alloc result-size)]
    (try
      (if (seq params)
        (let [pstmt (ffi/alloc (ffi/sizeof :pointer))]
          (try
            (when-not (zero? (c-prepare (:conn conn) sql pstmt))
              (let [stmt (ffi/read pstmt :pointer)
                    msg (c-prepare-error stmt)]
                (c-destroy-prepare pstmt)
                (throw (ex-info (str "duckdb: " msg) {:sql sql}))))
            (let [stmt (ffi/read pstmt :pointer)]
              (doseq [[i v] (map-indexed vector params)]
                (let [i (inc i)
                      rc (cond
                           (nil? v) (c-bind-null stmt i)
                           (integer? v) (c-bind-int64 stmt i v)
                           (float? v) (c-bind-double stmt i v)
                           (string? v) (c-bind-varchar stmt i v)
                           (boolean? v) (c-bind-int64 stmt i (if v 1 0))
                           :else (throw (ex-info (str "duckdb: cannot bind " (type v))
                                                 {:value v})))]
                  (when-not (zero? rc)
                    (throw (ex-info "duckdb: bind failed" {:sql sql :param v})))))
              (when-not (zero? (c-execute-prepared stmt res))
                (throw (ex-info (str "duckdb: " (c-result-error res)) {:sql sql}))))
            (finally
              (c-destroy-prepare pstmt)
              (ffi/free pstmt))))
        (when-not (zero? (c-query (:conn conn) sql res))
          (throw (ex-info (str "duckdb: " (c-result-error res)) {:sql sql}))))
      (if collect-rows?
        (read-rows res)
        {:rows-changed (c-rows-changed res)})
      (finally
        (c-destroy-result res)
        (ffi/free res)))))

(defn- with-conn [db-or-path f]
  (if (map? db-or-path)
    (f db-or-path)
    (with-db [db db-or-path] (f db))))

(defn query
  "Runs a select. db is a connection from open, or a path (opened and closed
  around the call). q is a SQL string or a [sql & params] vector. Returns a
  vector of maps with keywordized column names."
  [db q]
  (with-conn db (fn [db] (run* db q true))))

(defn execute!
  "Runs a statement. Arguments as in query. Returns {:rows-changed n}."
  [db q]
  (with-conn db (fn [db] (run* db q false))))
