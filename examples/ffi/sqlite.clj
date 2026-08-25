(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-library {:mac "libsqlite3.dylib" :linux "libsqlite3.so.0"})

(defcfn sqlite3-libversion "sqlite3_libversion" [] :string)
(defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)
(defcfn sqlite3-close "sqlite3_close" [:pointer] :int)
(defcfn sqlite3-exec "sqlite3_exec" [:pointer :string :pointer :pointer :pointer] :int)
(defcfn sqlite3-errmsg "sqlite3_errmsg" [:pointer] :string)

(println "sqlite version:" (sqlite3-libversion))

(def db
  (let [pp (ffi/alloc (ffi/sizeof :pointer))]
    (try (let [rc (sqlite3-open ":memory:" pp)]
           (assert (zero? rc) (str "open failed: " rc))
           (ffi/read pp :pointer))
         (finally (ffi/free pp)))))

(defn exec! [sql cb]
  (let [rc (sqlite3-exec db sql (or cb ffi/null) ffi/null ffi/null)]
    (when-not (zero? rc)
      (throw (ex-info (sqlite3-errmsg db) {:rc rc :sql sql})))))

(exec! "create table langs (name text, kind text)" nil)
(exec! "insert into langs values ('clojure','jvm'),('babashka','native'),('jolt','scheme')" nil)

(def rows (atom []))
;; int callback(void* _, int argc, char** argv, char** cols)
(def row-cb
  (ffi/callback (fn [_ argc argv _cols]
                  ;; argv comes from C with size 0; it holds argc pointers
                  (let [argv (ffi/reinterpret argv (* argc (ffi/sizeof :pointer)))]
                    (swap! rows conj
                           (mapv #(ffi/read argv :string (* % (ffi/sizeof :pointer)))
                                 (range argc))))
                  0)
                [:pointer :int :pointer :pointer] :int))

(exec! "select name, kind from langs order by name" row-cb)
(println "rows:" @rows)
(println (if (= [["babashka" "native"] ["clojure" "jvm"] ["jolt" "scheme"]] @rows)
           "SQLITE OK" "SQLITE FAIL"))
(sqlite3-close db)
