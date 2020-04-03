(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.impl.bencode.core :as bencode]
   [babashka.impl.nrepl-server :refer [start-server! stop-server!]]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [cheshire.core :as cheshire]
   [clojure.java.shell :refer [sh]]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.impl.opts :refer [init]])
  (:import [java.lang ProcessBuilder$Redirect]))

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
        (bencode/write-bencode os {"op" "eval" "code" "(foo)" "ns" "foo" "session" session "id" 3})
        (is (= ":foo" (:value (read-reply in session 3)))))
      (testing "complete"
        (testing "completions for fo"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "fo"
                                     "session" session
                                     "id" 4
                                     "ns" "foo"})
          (let [reply (read-reply in session 4)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["foo" "foo"]))
            (is (contains? completions ["clojure.core" "format"]))))
        (testing "completions for quux should be empty"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "quux"
                                     "session" session "id" 6
                                     "ns" "foo"})
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
              (is (contains? completions ["cheshire.core" "quux/generate-string"]))))))
      #_(testing "interrupt" ;; .stop doesn't work on Thread in GraalVM, this is why we can't have this yet
          (bencode/write-bencode os {"op" "eval" "code" "(range)" "session" session "id" 9})
          (Thread/sleep 1000)
          (bencode/write-bencode os {"op" "interrupt" "session" session "interrupt-id" 9 "id" 10})
          (is (contains? (set (:status (read-reply in session 10))) "done"))))))

(deftest nrepl-server-test
  (let [proc-state (atom nil)]
    (try
      (if tu/jvm?
        (future
          (start-server!
           (init {:namespaces {'cheshire.core {'generate-string cheshire/generate-string}}
                  :features #{:bb}}) "0.0.0.0:1667"))
        (let [pb (ProcessBuilder. ["./bb" "--nrepl-server" "0.0.0.0:1667"])
              _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
              ;; _ (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
              ;; env (.environment pb)
              ;; _ (.put env "BABASHKA_DEV" "true")
              proc (.start pb)]
          (reset! proc-state proc)))
      (babashka.wait/wait-for-port "localhost" 1667)
      (nrepl-test)
      (finally
        (if tu/jvm?
          (stop-server!)
          (when-let [proc @proc-state]
            (.destroy ^Process proc)))))))

;;;; Scratch

(comment
  )
