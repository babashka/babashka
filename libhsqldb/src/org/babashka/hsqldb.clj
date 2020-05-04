(ns org.babashka.hsqldb
  (:refer-clojure :exclude [read read-string])
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn]
            [next.jdbc :as jdbc])
  (:import [java.io PushbackInputStream])
  (:gen-class))

;; $ lein run -m org.babashka.hsqldb <<< $(bb '(do (bencode/write-bencode System/out {"op" "describe"}) nil)') | bb '
;; (defn read-map [m] (zipmap (map keyword (keys m)) (map #(String. %) (vals m))))
;; (read-map (bencode/read-bencode (java.io.PushbackInputStream. System/in)))'
;; => {:format "edn"}

;; $ lein run -m org.babashka.hsqldb <<< $(bb '(do (bencode/write-bencode System/out {"op" "invoke" "var" "hsqldb.jdbc/execute!" "args" "[\"jdbc:hsqldb:mem:testdb;sql.syntax_mys=true\" [\"create table foo ( foo int );\"]]"}) nil)') | bb '(defn read-map [m] (zipmap (map keyword (keys m)) (map #(String. %) (vals m)))) (read-map (bencode/read-bencode (java.io.PushbackInputStream. System/in)))'
;; => {:value "[#:next.jdbc{:update-count 0}]"}

(set! *warn-on-reflection* true)

(def stdin (PushbackInputStream. System/in))

(def lookup
  {'pod.hsqldb/execute! jdbc/execute!})

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(defn -main [& _args]
  (loop []
    (let [message (try (read)
                       (catch java.io.EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)]
          (case op
            :describe (do (write {"format" "edn"
                                  "vars" [{"namespace" "pod.hsqldb"
                                           "name" "execute!"}]})
                          (recur))
            :invoke (let [var (-> (get message "var")
                                  read-string
                                  symbol)
                          id (-> (get message "id")
                                 read-string)
                          args (get message "args")
                          args (read-string args)
                          args (edn/read-string args)]
                      (write {"value" (pr-str (apply (lookup var) args))
                              "id" id
                              "status" ["done"]})
                      (recur))))))))
