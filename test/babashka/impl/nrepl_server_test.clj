(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.fs :as fs]
   [babashka.main :as main]
   [babashka.process :as p]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [bencode.core :as bencode]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]])
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
              babashka-version (bytes->str (get versions "babashka"))
              ops (:ops msg)]
          (is (= 1 id))
          (is (= main/version babashka-version))
          (is (contains? ops "classpath"))))
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
            (is (contains? completions ["clojure.test" "test/deftest"]))))
        (testing "keyword completions"
          ;; Intern a test keyword so it's in the keyword table
          (bencode/write-bencode os {"op" "eval"
                                     "code" ":bb-nrepl-kw-test/alpha :bb-nrepl-kw-test/beta"
                                     "session" session
                                     "id" (new-id!)})
          (read-reply in session @id)
          (read-reply in session @id)
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" ":bb-nrepl-kw-test/a"
                                     "session" session
                                     "id" (new-id!)})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                candidates (into #{} (map :candidate) completions)]
            (is (contains? candidates ":bb-nrepl-kw-test/alpha"))
            (is (not (contains? candidates ":bb-nrepl-kw-test/beta")))
            (is (every? #(= "keyword" (:type %)) completions))))
        (testing "keyword query does not return class completions"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" ":Str"
                                     "session" session
                                     "id" (new-id!)})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)]
            (is (not (some #(= "class" (:type %)) completions))))))
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
            (is (= "Hello\n" (tu/normalize (:out reply))))))
        (testing "re-bind clojure.test/*test-out* to print-writer"
          (bencode/write-bencode os {"op" "eval" "code" "(= *out* clojure.test/*test-out*)"
                                     "session" session "id" (new-id!)})
          (is (= "true" (:value (read-reply in session @id))))))
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

(defn- eval-over-the-wire
  "Evaluates `code` in the server on `port`, for stopping the in-process
  server the JVM branch starts through bb's own entry point."
  [port code]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "clone"})
    (let [session (:new-session (read-msg (bencode/read-bencode in)))]
      (bencode/write-bencode os {"op" "eval" "code" code "session" session "id" "stop"})
      ;; the server closes the connection before it can reply
      (try (read-reply in session "stop")
           (catch java.io.EOFException _ nil)))))

(deftest ^:skip-windows nrepl-server-test
  (let [proc-state (atom nil)]
    (try
      (if tu/jvm?
        ;; in-process, through bb's own entry point, which returns while the
        ;; server's keepalive thread runs
        (tu/bb nil "-e" "(def nrepl-server (babashka.nrepl.server/start-server! {:host \"0.0.0.0\" :port 1668 :quiet true}))")
        (let [pb (ProcessBuilder. ["./bb" "nrepl-server" "0.0.0.0:1668"])
              _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
              proc (.start pb)]
          (reset! proc-state proc)))
      (babashka.wait/wait-for-port "127.0.0.1" 1668)
      (nrepl-test)
      (finally
        (if tu/jvm?
          (eval-over-the-wire 1668 "(babashka.nrepl.server/stop-server! user/nrepl-server)")
          (when-let [proc @proc-state]
            (.destroy ^Process proc)))))))

(defn- with-bb-script
  "Runs `script` through bb, which starts an nREPL server on `port` and
  returns, then calls `f`. In-process on the JVM, a process natively."
  [port script f]
  (let [file (str (fs/create-temp-file {:suffix ".clj"}))
        proc (atom nil)]
    (spit file script)
    (try
      (if tu/jvm?
        (tu/bb nil file)
        (reset! proc (p/process ["./bb" file] {:err :inherit})))
      (wait/wait-for-port "127.0.0.1" port {:timeout 10000})
      (f)
      (finally
        (if tu/jvm?
          (eval-over-the-wire port "(babashka.nrepl.server/stop-server! user/server)")
          (some-> @proc p/destroy-tree))
        (fs/delete-if-exists file)))))

(defn- session-sender
  "Clones a session over `in` and `os` and returns a function sending an op
  map to it and returning the replies."
  [in os]
  (bencode/write-bencode os {"op" "clone"})
  (let [session (:new-session (read-msg (bencode/read-bencode in)))
        id (atom 0)]
    (fn [m]
      (let [id (str (swap! id inc))]
        (bencode/write-bencode os (assoc m "session" session "id" id))
        (loop [replies []]
          (let [reply (read-reply in session id)
                replies (conj replies reply)]
            (if (contains? (set (:status reply)) "done")
              replies
              (recur replies))))))))

(defn- with-session
  "Calls `f` with a function sending an op map to a fresh session on
  `port` and returning the replies to it."
  [port f]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (f (session-sender in os))))

(defn- with-unix-session
  "Like `with-session`, over the unix domain socket at `path`."
  [path f]
  (with-open [ch (java.nio.channels.SocketChannel/open
                  (java.net.UnixDomainSocketAddress/of ^String path))
              in (java.io.PushbackInputStream. (java.nio.channels.Channels/newInputStream ch))
              os (java.nio.channels.Channels/newOutputStream ch)]
    (f (session-sender in os))))

(deftest ^:skip-windows nrepl-user-middleware-test
  (with-bb-script 1670
    "(require '[nrepl.middleware :refer [set-descriptor!]] '[nrepl.transport :as t])
     (defn wrap-hello [handler]
       (fn [{:keys [op] :as msg}]
         (if (= \"hello\" op)
           (t/respond-to msg :greeting (str \"Hello, \" (:name msg \"world\") \"!\") :status :done)
           (handler msg))))
     (set-descriptor! #'wrap-hello {:requires #{\"clone\"} :expects #{} :handles {\"hello\" {:doc \"Greets.\"}}})
     (defn wrap-timing [handler]
       (fn [{:keys [op transport] :as msg}]
         (if (= \"eval\" op)
           (handler (assoc msg :transport (reify t/Transport
                                            (recv [_ timeout] (t/recv transport timeout))
                                            (send [this reply]
                                              (t/send transport (cond-> reply (contains? (set (:status reply)) :done) (assoc :elapsed-ms 0)))
                                              this))))
           (handler msg))))
     (set-descriptor! #'wrap-timing {:requires #{\"clone\"} :expects #{\"eval\"} :handles {}})
     (def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1670 :quiet true :middleware [#'wrap-hello #'wrap-timing]}))"
    (fn []
      (with-session 1670
        (fn [send]
          (testing "a middleware adding an op"
            (is (= "Hello, bb!" (:greeting (first (send {"op" "hello" "name" "bb"})))))
            (is (contains? (:ops (first (send {"op" "describe"}))) "hello")))
          (testing "a middleware wrapping eval's transport"
            (let [replies (send {"op" "eval" "code" "(+ 1 2)"})]
              (is (= "3" (:value (first replies))))
              (is (= 0 (:elapsed-ms (last replies)))))))))))

(deftest ^:skip-windows nrepl-eval-stack-depth-test
  (with-bb-script 1672
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1672 :quiet true}))"
    (fn []
      (with-session 1672
        (fn [send]
          (testing "an eval recurses 5000 calls deep"
            (is (= ["#'user/down" ":bottom"]
                   (keep :value (send {"op" "eval" "code" "(defn down [n] (if (zero? n) :bottom (down (dec n)))) (down 5000)"}))))))))))

(deftest ^:skip-windows nrepl-eval-error-test
  (with-bb-script 1673
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1673 :quiet true}))"
    (fn []
      (with-session 1673
        (fn [send]
          (send {"op" "eval" "code" "(defn inner [x] (/ x 0)) (defn middle [x] (inner x)) (defn outer [x] (middle x))"})
          (testing "an eval error names the exception class and the location"
            (let [replies (send {"op" "eval" "code" "(outer 1)"})]
              (is (str/includes? (str/join (keep :err replies)) "java.lang.ArithmeticException: Divide by zero"))
              (is (str/includes? (str/join (keep :err replies)) "[at NO_SOURCE_PATH:1:"))
              (is (= "class java.lang.ArithmeticException" (some :ex replies)))))
          (testing "*e carries sci's callstack"
            (is (= ["true"] (keep :value (send {"op" "eval" "code" "(some? (:sci.impl/callstack (ex-data *e)))"})))))
          (testing "analyze-last-stacktrace answers with sci's frames"
            (let [[cause] (send {"op" "analyze-last-stacktrace"})]
              (is (= "java.lang.ArithmeticException" (bytes->str (:class cause))))
              (is (= ["user/inner" "user/middle" "user/outer"]
                     (->> (:stacktrace cause)
                          (map #(bytes->str (get % "name")))
                          (filter #(str/starts-with? % "user/"))
                          distinct
                          (take 3))))
              (is (= #{"NO_SOURCE_PATH"}
                     (->> (:stacktrace cause)
                          (filter #(str/starts-with? (bytes->str (get % "name")) "user/"))
                          (map #(bytes->str (get % "file")))
                          set))))))))))

(deftest ^:skip-windows nrepl-cider-ops-test
  (with-bb-script 1671
    "(ns ct-demo (:require [clojure.test :refer [deftest is testing]]))
     (deftest passing (is (= 1 1)))
     (deftest failing (testing \"ctx\" (is (= 1 2) \"nope\")))
     (deftest erroring (is (= 1 (throw (ex-info \"boom\" {:a 1})))))
     (ns user)
     (def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1671 :quiet true}))"
    (fn []
      (with-session 1671
        (fn [send]
          (testing "describe carries the cider version CIDER checks"
            (is (string? (bytes->str (get-in (first (send {"op" "describe"})) [:aux "cider-version" "version-string"])))))
          (testing "the test op runs clojure.test and reports like cider-nrepl"
            (let [reply (first (send {"op" "test" "ns" "ct-demo"}))
                  summary (into {} (map (fn [[k v]] [(keyword (bytes->str k)) v])) (:summary reply))
                  results (get (:results reply) "ct-demo")
                  result (fn [var] (into {} (map (fn [[k v]] [(keyword (bytes->str k)) (if (bytes? v) (bytes->str v) v)])) (first (get results var))))]
              (is (= {:ns 1 :var 3 :test 3 :pass 1 :fail 1 :error 1} summary))
              (is (= "pass" (:type (result "passing"))))
              (is (= "ctx" (:context (result "failing"))))
              (is (= "nope" (:message (result "failing"))))
              (is (= "error" (:type (result "erroring"))))
              (is (str/includes? (:error (result "erroring")) "boom"))
              (is (integer? (:line (result "erroring"))))))
          (testing "test-stacktrace starts in the erring test's namespace"
            (let [cause (first (send {"op" "test-stacktrace" "ns" "ct-demo" "var" "erroring" "index" 0}))]
              (is (= "boom" (bytes->str (:message cause))))
              (is (= "ct-demo" (bytes->str (get-in (first (:stacktrace cause)) ["ns"]))))))
          (testing "retest reruns only what failed"
            (let [summary (into {} (map (fn [[k v]] [(keyword (bytes->str k)) v])) (:summary (first (send {"op" "retest"}))))]
              (is (= 2 (:test summary)))
              (is (= 0 (:pass summary)))))
          (testing "an eval with :inspect returns the inspector's rendering"
            (let [value (:value (first (send {"op" "eval" "code" "{:a 1 :b [1 2 3]}" "inspect" "true"})))]
              (is (str/starts-with? value "(\"Class: \""))
              (is (str/includes? value "\":b\""))))
          (testing "inspect-push, then inspect-pop"
            (let [pushed (:value (first (send {"op" "inspect-push" "idx" 4})))
                  popped (:value (first (send {"op" "inspect-pop"})))]
              (is (str/includes? pushed "PersistentVector"))
              (is (str/includes? popped "PersistentArrayMap"))))
          (testing "inspect-def-current-value defs it"
            (send {"op" "inspect-def-current-value" "ns" "user" "var-name" "inspected"})
            (is (= "{:a 1, :b [1 2 3]}" (:value (first (send {"op" "eval" "code" "inspected"})))))))))))

(deftest ^:skip-windows nrepl-unix-socket-test
  ;; macOS limits a socket path to 104 bytes, its temp dir is longer
  (let [path (str "/tmp/bb-nrepl-" (System/nanoTime) ".sock")
        file (str (fs/create-temp-file {:suffix ".clj"}))
        proc (atom nil)
        stop! (fn []
                (with-unix-session path
                  (fn [send]
                    (try (send {"op" "eval" "code" "(babashka.nrepl.server/stop-server! user/server)"})
                         (catch java.io.EOFException _ nil)))))]
    (spit file (format "(def server (babashka.nrepl.server/start-server! {:socket %s :quiet true}))" (pr-str path)))
    (try
      (if tu/jvm?
        (tu/bb nil file)
        (reset! proc (p/process ["./bb" file] {:err :inherit})))
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (while (and (not (fs/exists? path)) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50)))
      (is (fs/exists? path))
      (with-unix-session path
        (fn [send]
          (is (= ["3"] (keep :value (send {"op" "eval" "code" "(+ 1 2)"}))))
          (is (contains? (:ops (first (send {"op" "describe"}))) "eval"))))
      (finally
        (if tu/jvm?
          (stop!)
          (some-> @proc p/destroy-tree))
        (fs/delete-if-exists path)
        (fs/delete-if-exists file)))))

(deftest ^:skip-windows nrepl-server-non-daemon-test
  (when tu/native?
    (let [proc (p/process ["./bb" "-e"
                           "(babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1669})"])]
      (try
        (is (wait/wait-for-port "127.0.0.1" 1669 {:timeout 5000})
            "nREPL server should stay alive without @(promise)")
        (with-open [socket (java.net.Socket. "127.0.0.1" 1669)
                    in (.getInputStream socket)
                    in (java.io.PushbackInputStream. in)
                    os (.getOutputStream socket)]
          (bencode/write-bencode os {"op" "clone"})
          (let [session (:new-session (read-msg (bencode/read-bencode in)))]
            (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)"
                                       "session" session "id" "1"})
            (is (= "6" (:value (read-reply in session "1"))))))
        (finally
          (p/destroy-tree proc))))))

;;;; Scratch

(comment)
