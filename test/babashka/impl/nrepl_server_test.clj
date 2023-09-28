(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.fs :as fs]
   [babashka.impl.nrepl-server :refer [start-server!]]
   [babashka.main :as main]
   [babashka.nrepl.server :refer [parse-opt stop-server!]]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [bencode.core :as bencode]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.core :as sci]
   [sci.ctx-store :as ctx-store]
   [babashka.impl.classpath :as cp])
  (:import
   [java.lang ProcessBuilder$Redirect]))

(def debug? false)

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
              res)
        res (if-let [status (:sessions res)]
              (assoc res :sessions (mapv bytes->str status))
              res)
        res (if-let [cp (:classpath res)]
              (assoc res :classpath (mapv bytes->str cp))
              res)]
    res))

(defn read-reply [in session id]
  (loop []
    (let [msg (read-msg (bencode/read-bencode in))]
      (if (and (= (:session msg) session)
               (= (:id msg) id))
        (do
          (when debug? (prn "received" msg))
          msg)
        (do
          (when debug? (prn "skipping over msg" msg))
          (recur))))))

(defn nrepl-test []
  (with-open [socket (java.net.Socket. "127.0.0.1" 1668)
              in (.getInputStream socket)
              in (java.io.PushbackInputStream. in)
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "clone"})
    (let [session (:new-session (read-msg (bencode/read-bencode in)))
          id (atom 0)
          new-id! #(swap! id inc)]
      (testing "session"
        (is session))
      (testing "describe"
        (bencode/write-bencode os {"op" "describe" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              versions (:versions msg)
              babashka-version (bytes->str (get versions "babashka"))]
          (is (= 1 id))
          (is (= main/version babashka-version))))
      (testing "eval"
        (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              value (:value msg)]
          (is (= 2 id))
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
            (is (= ":foo0" (:value (read-reply in session @id)))))
          ;; TODO: I don't remember why we created a new ns
          #_(testing "providing an ns value of a non-existing namespace creates the namespace"
              (bencode/write-bencode os {"op" "eval"
                                         "code" "(ns-name *ns*)"
                                         "session" session
                                         "id" (new-id!)
                                         "ns" "unicorn"})
              (let [reply (read-reply in session @id)]
                (is (= "unicorn" (:value reply))))))
        (testing "multiple top level expressions results in two value replies"
          (bencode/write-bencode os {"op" "eval"
                                     "code" "(+ 1 2 3) (+ 1 2 3)"
                                     "session" session
                                     "id" (new-id!)})
          (let [reply-1 (read-reply in session @id)
                reply-2 (read-reply in session @id)]
            (is (= "6" (:value reply-1) (:value reply-2))))))
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
      (testing "close + ls-sessions"
        (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
        (let [reply (read-reply in session @id)
              sessions (set (:sessions reply))]
          (is (contains? sessions session))
          (let [new-sessions (loop [i 0
                                    sessions #{}]
                               (bencode/write-bencode os {"op" "clone" "session" session "id" (new-id!)})
                               (let [new-session (:new-session (read-reply in session @id))
                                     sessions (conj sessions new-session)]
                                 (if (= i 4)
                                   sessions
                                   (recur (inc i) sessions))))]
            (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
            (let [reply (read-reply in session @id)
                  sessions (set (:sessions reply))]
              (is (= 6 (count sessions)))
              (is (contains? sessions session))
              (is (= new-sessions (disj sessions session)))
              (testing "close"
                (doseq [close-session (disj sessions session)]
                  (bencode/write-bencode os {"op" "close" "session" close-session "id" (new-id!)})
                  (let [reply (read-reply in close-session @id)]
                    (is (contains? (set (:status reply)) "session-closed")))))
              (testing "session not listen in ls-sessions after close"
                (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
                (let [reply (read-reply in session @id)
                      sessions (set (:sessions reply))]
                  (is (contains? sessions session))
                  (is (not (some #(contains? sessions %) new-sessions)))))))))
      (testing "output"
        (bencode/write-bencode os {"op" "eval" "code" "(dotimes [i 3] (println \"Hello\"))"
                                   "session" session "id" (new-id!)})
        (dotimes [_ 3]
          (let [reply (read-reply in session @id)]
            (is (= "Hello\n" (tu/normalize (:out reply)))))))
      (testing "dynamic var can be set!, test unchecked-math"
        (bencode/write-bencode os {"op" "eval" "code" "(set! *unchecked-math* true)"
                                   "session" session "id" (new-id!)})
        (let [reply (read-reply in session @id)]
          (is (= "true" (:value reply)))))
      (testing "classpath op"
        (bencode/write-bencode os {"op" "eval" "code" "(babashka.classpath/add-classpath \"test-resources/babashka/src_for_classpath_test\")"
                                   "session" session "id" (new-id!)})
        (read-reply in session @id)
        (bencode/write-bencode os {"op" "classpath"
                                   "session" session "id" (new-id!)})
        (let [reply (read-reply in session @id)
              cp (:classpath reply)]
          (is (every? string? cp))
          (is (pos? (count cp)))
          ;; dev-resources doesn't exist
          (is (pos? (count (filter fs/exists? cp)))))))))

(deftest ^:skip-windows nrepl-server-test
  (let [proc-state (atom nil)
        server-state (atom nil)
        ctx (sci/init {:namespaces main/namespaces
                       :features #{:bb}})]
    (sci.ctx-store/with-ctx ctx
      (try
        (if tu/jvm?
          (let [nrepl-opts (parse-opt "0.0.0.0:1668")
                nrepl-opts (assoc nrepl-opts
                                  :describe {"versions" {"babashka" main/version}})
                server (start-server! nrepl-opts)]
            (reset! server-state server))
          (let [pb (ProcessBuilder. ["./bb" "nrepl-server" "0.0.0.0:1668"])
                _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
                ;; _ (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
                ;; env (.environment pb)
                ;; _ (.put env "BABASHKA_DEV" "true")
                proc (.start pb)]
            (reset! proc-state proc)))
        (babashka.wait/wait-for-port "localhost" 1668)
        (nrepl-test)
        (finally
          (if tu/jvm?
            (stop-server! @server-state)
            (when-let [proc @proc-state]
              (.destroy ^Process proc))))))))

;;;; Scratch

(comment)
