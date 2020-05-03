(ns test
  (:refer-clojure :exclude [read])
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn])
  #_(:import java.lang.ProcessBuilder$Redirect))

(defn write [stream v]
  (bencode/write-bencode stream v)
  (.flush stream))

(defn read [stream]
  (bencode/read-bencode stream))

(defn query [stream q]
  (write stream {"op" "invoke"
                 "var" "hsqldb.jdbc/execute!"
                 "args" (pr-str ["jdbc:hsqldb:mem:testdb;sql.syntax_mys=true" q])}))

(let [pb (ProcessBuilder. #_["lein" "run" "-m" "org.babashka.hsqldb"]
                          ["./hsqldb-babashka-plugin"])
      _ (.redirectErrorStream pb true)
      ;; _ (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
      p (.start pb)
      stdin (.getOutputStream p)
      stdout (.getInputStream p)
      stdout (java.io.PushbackInputStream. stdout)]
  (write stdin {"op" "describe"})
  (let [reply (read stdout)]
    (println "format:" (String. (get reply "format")))) ;;=> edn

  (query stdin ["create table foo ( foo int );"])
  (let [reply (read stdout)]
    (println "reply:" (edn/read-string (String. (get reply "value"))))) ;;=>  [{:next.jdbc/update-count 0}]

  (query stdin ["insert into foo values ( 1, 2, 3);"])
  (let [reply (read stdout)]
    (println "reply:" (edn/read-string (String. (get reply "value"))))) ;;=> [{:next.jdbc/update-count 3}]

  (query stdin ["select * from foo;"])
  (let [reply (read stdout)]
    (println "reply:" (edn/read-string (String. (get reply "value")))))) ;=> [{:FOO/FOO 1} {:FOO/FOO 2} {:FOO/FOO 3}]
