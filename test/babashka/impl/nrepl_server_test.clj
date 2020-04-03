(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.impl.bencode.core :as bencode]
   [babashka.impl.nrepl-server :refer [start-server! stop-server!]]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [cheshire.core :as cheshire]
   [clojure.java.shell :refer [sh]]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn bytes->str [x]
  (if (bytes? x) (String. (bytes x))
      (str x)))

(defn read-msg [msg]
  (let [res (zipmap (map keyword (keys msg))
                    (map #(if (bytes? %)
                            (String. (bytes %))
                            %)
                         (vals msg)))
        res (if-let [status (:status res)]
              (assoc res :status (mapv bytes->str status))
              res)]
    res))

(defn read-reply [in session id]
  (loop []
    (let [msg (read-msg (bencode/read-bencode in))]
      (if (and (= (:session msg) session)
               (= (:id msg) id))
        msg
        (recur)))))

(defn nrepl-test []
  (with-open [socket (java.net.Socket. "127.0.0.1" 1667)
              in (.getInputStream socket)
              in (java.io.PushbackInputStream. in)
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "clone"})
    (let [session (:new-session (read-msg (bencode/read-bencode in)))]
      (testing "session"
        (is session))
      (testing "eval"
        (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)" "session" session "id" 1})
        (let [msg (read-reply in session 1)
              id (:id msg)
              value (:value msg)]
          (is (= 1 id))
          (is (= value "6"))))
      (testing "load-file"
        (bencode/write-bencode os {"op" "load-file" "file" "(ns foo) (defn foo [] :foo)" "session" session "id" 2})
        (read-reply in session 2)
        (bencode/write-bencode os {"op" "eval" "code" "(foo)" "session" session "id" 3})
        (is (= ":foo" (:value (read-reply in session 3)))))
      (testing "complete"
        (testing "completions for fo"
          (bencode/write-bencode os {"op" "complete" "symbol" "fo" "session" session "id" 4})
          (let [reply (read-reply in session 4)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["foo" "foo"]))
            (is (contains? completions ["clojure.core" "format"]))))
        (testing "completions for namespace"
          (bencode/write-bencode os {"op" "complete" "symbol" "cheshire." "session" session "id" 5})
          (let [reply (read-reply in session 5)
                completions (:completions reply)
                completions (mapv read-msg completions)]
            ;; TODO:
            (prn completions)))
        (testing "completions for quux should be empty"
          (bencode/write-bencode os {"op" "complete" "symbol" "quux" "session" session "id" 6})
          (let [reply (read-reply in session 6)
                completions (:completions reply)]
            (is (empty? completions)))
          (testing "unless quux is an alias"
            (bencode/write-bencode os {"op" "eval" "code" "(require '[cheshire.core :as quux])" "session" session "id" 7})
            (bencode/write-bencode os {"op" "complete" "symbol" "quux" "session" session "id" 8})
            (let [reply (read-reply in session 8)
                  completions (:completions reply)
                  completions (mapv read-msg completions)
                  completions (into #{} (map (juxt :ns :candidate)) completions)]
              (is (contains? completions ["cheshire.core" "quux/generate-string"])))))))))

#_#_versions   (dict
            clojure (dict
                     incremental    0
                     major          1
                     minor          10
                     version-string "1.10.0")
            java    (dict
                     incremental    "1"
                     major          "10"
                     minor          "0"
                     version-string "10.0.1")
            nrepl   (dict
                     incremental    0
                     major          0
                     minor          7
                     version-string "0.7.0-beta1"))

(deftest nrepl-server-test
  (try
    (if tu/jvm?
      (future
        (start-server!
         (init {:namespaces {'cheshire.core {'generate-string cheshire/generate-string}}
                :features #{:bb}}) "0.0.0.0:1667"))
      (future
        (prn (sh "bash" "-c"
                 "./bb --nrepl-server 0.0.0.0:1667"))))
    (babashka.wait/wait-for-port "localhost" 1667)
    (nrepl-test)
    (finally
      (if tu/jvm?
        (stop-server!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1667)")))))

;;;; Scratch

(comment
  )
