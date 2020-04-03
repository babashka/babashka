(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.impl.bencode.core :as bencode]
   [babashka.impl.nrepl-server :refer [start-server! stop-server!]]
   [babashka.test-utils :as tu]
   [clojure.java.shell :refer [sh]]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn try-connect ^java.net.Socket [host port max-attempts]
  (when (pos? max-attempts)
    (try (java.net.Socket. ^String host ^long port)
         (catch Exception _
           (Thread/sleep 500)
           (try-connect host port (dec max-attempts))))))

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
  (with-open [socket (try-connect "127.0.0.1" 1667 5)
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
        (is (= ":foo" (:value (read-reply in session 3))))))))

(deftest nrepl-server-test
  (try
    (if tu/jvm?
      (future
        (start-server! (init {:env (atom {})
                              :features #{:bb}}) "0.0.0.0:1667"))
      (future
        (prn (sh "bash" "-c"
                 "./bb --nrepl-server 0.0.0.0:1667"))))
    (nrepl-test)
    (finally
      (if tu/jvm?
        (stop-server!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1667)")))))

;;;; Scratch

(comment
  )
