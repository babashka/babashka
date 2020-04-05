(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.impl.bencode.core :as bencode]
   [babashka.impl.nrepl-server :refer [start-server! stop-server!]]
   [babashka.main :as main]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [cheshire.core :as cheshire]
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
    (let [session (:new-session (read-msg (bencode/read-bencode in)))
          id (atom 0)
          new-id! #(swap! id inc)]
      (testing "session"
        (is session))
      (testing "eval"
        (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              value (:value msg)]
          (is (= 1 id))
          (is (= value "6")))
        (testing "creating a namespace and evaluating something in it"
          (bencode/write-bencode os {"op" "eval"
                                     "code" "(ns ns0) (defn foo [] :foo0) (ns ns1) (defn foo [] :foo1)"
                                     "session" session
                                     "id" (new-id!)})
          (read-reply in session @id)
          (testing "not providing the ns key evaluates in the last defined namespace"
            (bencode/write-bencode os {"op" "eval" "code" "(foo)" "session" session "id" (new-id!)})
            (is (= ":foo1" (:value (read-reply in session @id)))))
          (testing "explicitly providing the ns key evaluates in that namespace"
            (bencode/write-bencode os {"op" "eval"
                                       "code" "(foo)"
                                       "session" session
                                       "id" (new-id!)
                                       "ns" "ns0"})
            (is (= ":foo0" (:value (read-reply in session @id)))))))
      (testing "load-file"
        (bencode/write-bencode os {"op" "load-file" "file" "(ns foo) (defn foo [] :foo)" "session" session "id" (new-id!)})
        (read-reply in session @id)
        (bencode/write-bencode os {"op" "eval" "code" "(foo)" "ns" "foo" "session" session "id" (new-id!)})
        (is (= ":foo" (:value (read-reply in session @id)))))
      (testing "complete"
        (testing "completions for fo"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "fo"
                                     "session" session
                                     "id" (new-id!)
                                     "ns" "foo"})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["foo" "foo"]))
            (is (contains? completions ["clojure.core" "format"]))))
        (testing "completions for quux should be empty"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "quux"
                                     "session" session "id" (new-id!)
                                     "ns" "foo"})
          (let [reply (read-reply in session @id)
                completions (:completions reply)]
            (is (empty? completions)))
          (testing "unless quux is an alias"
            (bencode/write-bencode os {"op" "eval" "code" "(require '[cheshire.core :as quux])" "session" session "id" (new-id!)})
            (read-reply in session @id)
            (bencode/write-bencode os {"op" "complete" "symbol" "quux" "session" session "id" (new-id!)})
            (let [reply (read-reply in session @id)
                  completions (:completions reply)
                  completions (mapv read-msg completions)
                  completions (into #{} (map (juxt :ns :candidate)) completions)]
              (is (contains? completions ["cheshire.core" "quux/generate-string"])))))
        (testing "completions for clojure.test"
          (bencode/write-bencode os {"op" "eval" "code" "(require '[clojure.test :as test])" "session" session "id" (new-id!)})
          (read-reply in session @id)
          (bencode/write-bencode os {"op" "complete" "symbol" "test" "session" session "id" (new-id!)})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["clojure.test" "test/deftest"])))))
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
           (init {:namespaces main/namespaces
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
