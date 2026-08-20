(ns babashka.sqlite
  "SQLite over babashka.ffi against the system libsqlite3. Userspace draft of
  what could ship in bb; the query/execute! surface is compatible with
  pod-babashka-go-sqlite3, so honeysql-formatted vectors work as-is:

      (require '[babashka.sqlite :as sqlite])
      (sqlite/execute! \"/tmp/foo.db\"
        [\"create table if not exists foo (the_text TEXT, the_int INTEGER)\"])
      (sqlite/execute! \"/tmp/foo.db\" [\"insert into foo values (?, ?)\" \"hi\" 1])
      (sqlite/query \"/tmp/foo.db\" [\"select * from foo where the_int = ?\" 1])
      ;; => [{:the_text \"hi\", :the_int 1}]

  A string db argument opens and closes the database around the call. For
  many operations, hold a connection instead:

      (sqlite/with-db [db \"/tmp/foo.db\"]
        (sqlite/execute! db [\"insert into foo values (?, ?)\" \"there\" 2])
        (sqlite/query db \"select count(*) c from foo\"))

  Parameters bind by type: integers, doubles, strings, nil, booleans
  (as 0/1), and byte arrays (as BLOB). Rows come back as maps with
  keywordized column names; BLOB columns come back as byte arrays.
  busy_timeout is set to 5000 ms on open."
  (:refer-clojure :exclude [read])
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(when-not (some #(try (ffi/load-library %) (catch Exception _ nil))
                ["libsqlite3.dylib"
                 "/usr/lib/libsqlite3.dylib"
                 "libsqlite3.so.0"
                 "libsqlite3.so"])
  (throw (ex-info "libsqlite3 not found" {})))

(defcfn ^:private c-open "sqlite3_open" [:string :pointer] :int)
(defcfn ^:private c-close "sqlite3_close_v2" [:pointer] :int)
(defcfn ^:private c-busy-timeout "sqlite3_busy_timeout" [:pointer :int] :int)
(defcfn ^:private c-errmsg "sqlite3_errmsg" [:pointer] :string)
(defcfn ^:private c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(defcfn ^:private c-step "sqlite3_step" [:pointer] :int)
(defcfn ^:private c-finalize "sqlite3_finalize" [:pointer] :int)
(defcfn ^:private c-changes "sqlite3_changes" [:pointer] :int)
(defcfn ^:private c-last-rowid "sqlite3_last_insert_rowid" [:pointer] :int64)
(defcfn ^:private c-bind-int64 "sqlite3_bind_int64" [:pointer :int :int64] :int)
(defcfn ^:private c-bind-double "sqlite3_bind_double" [:pointer :int :double] :int)
(defcfn ^:private c-bind-text "sqlite3_bind_text" [:pointer :int :string :int :pointer] :int)
(defcfn ^:private c-bind-blob "sqlite3_bind_blob" [:pointer :int :pointer :int :pointer] :int)
(defcfn ^:private c-bind-null "sqlite3_bind_null" [:pointer :int] :int)
(defcfn ^:private c-column-count "sqlite3_column_count" [:pointer] :int)
(defcfn ^:private c-column-name "sqlite3_column_name" [:pointer :int] :string)
(defcfn ^:private c-column-type "sqlite3_column_type" [:pointer :int] :int)
(defcfn ^:private c-column-int64 "sqlite3_column_int64" [:pointer :int] :int64)
(defcfn ^:private c-column-double "sqlite3_column_double" [:pointer :int] :double)
(defcfn ^:private c-column-text "sqlite3_column_text" [:pointer :int] :string)
(defcfn ^:private c-column-blob "sqlite3_column_blob" [:pointer :int] :pointer)
(defcfn ^:private c-column-bytes "sqlite3_column_bytes" [:pointer :int] :int)

(def ^:private SQLITE-ROW 100)
(def ^:private SQLITE-DONE 101)
(def ^:private SQLITE-TRANSIENT -1)

(defn open
  "Opens (creating if needed) the database at path. Returns a connection
  handle for use with execute!, query and close!."
  [path]
  (let [pp (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (let [rc (c-open path pp)
            db (ffi/read pp :pointer)]
        (when-not (zero? rc)
          (let [msg (c-errmsg db)]
            (c-close db)
            (throw (ex-info (str "sqlite: cannot open " path ": " msg) {:rc rc}))))
        (c-busy-timeout db 5000)
        db)
      (finally (ffi/free pp)))))

(defn close!
  "Closes a connection returned by open."
  [db]
  (c-close db)
  nil)

(defmacro with-db
  "(with-db [db \"/tmp/foo.db\"] ...) - opens the database, binds the
  connection, closes it after the body."
  [[sym path] & body]
  `(let [~sym (open ~path)]
     (try ~@body
          (finally (close! ~sym)))))

(defn- sqlite-error [db op]
  (ex-info (str "sqlite: " op " failed: " (c-errmsg db)) {}))

(defn- bind-param! [db stmt i v]
  (let [rc (cond
             (nil? v) (c-bind-null stmt i)
             (integer? v) (c-bind-int64 stmt i v)
             (float? v) (c-bind-double stmt i v)
             (string? v) (c-bind-text stmt i v -1 SQLITE-TRANSIENT)
             (boolean? v) (c-bind-int64 stmt i (if v 1 0))
             (bytes? v) (let [n (alength ^bytes v)
                              p (ffi/alloc (max n 1))]
                          (try
                            (dotimes [j n]
                              (ffi/write p :int8 j (aget ^bytes v j)))
                            (c-bind-blob stmt i p n SQLITE-TRANSIENT)
                            (finally (ffi/free p))))
             :else (throw (ex-info (str "sqlite: cannot bind parameter of type "
                                        (type v))
                                   {:value v})))]
    (when-not (zero? rc)
      (throw (sqlite-error db "bind")))))

(defn- prepare* [db sql params]
  (let [pp (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (when-not (zero? (c-prepare db sql -1 pp ffi/null))
        (throw (sqlite-error db "prepare")))
      (let [stmt (ffi/read pp :pointer)]
        (doseq [[i v] (map-indexed vector params)]
          (bind-param! db stmt (inc i) v))
        stmt)
      (finally (ffi/free pp)))))

(defn- column-value [stmt i]
  (case (long (c-column-type stmt i))
    1 (c-column-int64 stmt i)
    2 (c-column-double stmt i)
    3 (c-column-text stmt i)
    4 (let [n (c-column-bytes stmt i)
            p (c-column-blob stmt i)
            out (byte-array n)]
        (dotimes [j n]
          (aset out j (unchecked-byte (ffi/read p :uint8 j))))
        out)
    5 nil))

(defn- run-stmt [db stmt collect-rows?]
  (let [cols (when collect-rows?
               (mapv #(keyword (c-column-name stmt %))
                     (range (c-column-count stmt))))]
    (loop [rows (transient [])]
      (let [rc (long (c-step stmt))]
        (cond
          (= SQLITE-ROW rc)
          (recur (if collect-rows?
                   (conj! rows (zipmap cols (map #(column-value stmt %)
                                                 (range (count cols)))))
                   rows))
          (= SQLITE-DONE rc) (persistent! rows)
          :else (throw (sqlite-error db "step")))))))

(defn- sqlvec [q]
  (cond
    (string? q) [q]
    (vector? q) q
    :else (throw (ex-info "sqlite: expected a SQL string or [sql & params] vector"
                          {:got (type q)}))))

(defn- with-conn [db-or-path f]
  (if (string? db-or-path)
    (with-db [db db-or-path] (f db))
    (f db-or-path)))

(defn query
  "Runs a select. db is a connection from open, or a path (opened and closed
  around the call). q is a SQL string or a [sql & params] vector, e.g. from
  honey.sql/format. Returns a vector of maps with keywordized column names."
  [db q]
  (with-conn db
    (fn [db]
      (let [[sql & params] (sqlvec q)
            stmt (prepare* db sql params)]
        (try
          (run-stmt db stmt true)
          (finally (c-finalize stmt)))))))

(defn execute!
  "Runs a statement. Arguments as in query. Returns
  {:rows-affected n, :last-inserted-id id}."
  [db q]
  (with-conn db
    (fn [db]
      (let [[sql & params] (sqlvec q)
            stmt (prepare* db sql params)]
        (try
          (run-stmt db stmt false)
          {:rows-affected (c-changes db)
           :last-inserted-id (c-last-rowid db)}
          (finally (c-finalize stmt)))))))
