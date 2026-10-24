(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.fs :as fs]
   [babashka.impl.common :as common]
   [babashka.impl.repl :as repl]
   [babashka.main :as main]
   [babashka.nrepl.server :as nrepl-server]
   [babashka.process :as p]
   [babashka.test-utils :as tu]
   [babashka.wait :as wait]
   [bencode.core :as bencode]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [clojure.tools.reader.reader-types :as r]
   [sci.core :as sci])
  (:import
   [java.lang ProcessBuilder$Redirect]
   [org.jline.reader LineReader EndOfFileException UserInterruptException]))

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
  (with-open [socket (doto (java.net.Socket. "127.0.0.1" 1668)
                       ;; a missed reply fails here instead of hanging CI
                       (.setSoTimeout 30000))
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
          (let [before sessions
                new-sessions (loop [i 0
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
              (is (= (+ 5 (count before)) (count sessions)))
              (is (contains? sessions session))
              (is (= new-sessions (set/difference sessions before)))
              (testing "close"
                (doseq [close-session new-sessions]
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
          (is (pos? (count (filter fs/exists? cp))))))
      (testing "stdin"
        (let [eval-with-stdin
              ;; the eval's done and the stdin op's done arrive in either
              ;; order, so both are awaited from one stream of replies
              (fn [code stdin]
                (let [eval-id (new-id!)]
                  (bencode/write-bencode os {"op" "eval" "code" code "session" session "id" eval-id})
                  (loop [asked 0 value nil pending #{eval-id}]
                    (let [msg (read-msg (bencode/read-bencode in))
                          status (set (:status msg))
                          own? (and (= session (:session msg)) (contains? pending (:id msg)))
                          stdin-id (when (and own? (contains? status "need-input"))
                                     (let [stdin-id (new-id!)]
                                       (bencode/write-bencode os {"op" "stdin" "stdin" stdin
                                                                  "session" session "id" stdin-id})
                                       stdin-id))
                          asked (cond-> asked stdin-id inc)
                          value (or (when own? (:value msg)) value)
                          pending (cond-> pending
                                    stdin-id (conj stdin-id)
                                    (and own? (contains? status "done")) (disj (:id msg)))]
                      (if (empty? pending)
                        {:asked asked :value value}
                        (recur asked value pending))))))]
          (testing "a form is read from one stdin chunk, asked for once"
            (let [{:keys [asked value]} (eval-with-stdin "(read)" ":ohai\n")]
              (is (= 1 asked))
              (is (= ":ohai" value))))
          (testing "the newline after the form is not left for the next read"
            (bencode/write-bencode os {"op" "stdin" "stdin" "a\n" "session" session "id" (new-id!)})
            (read-reply in session @id)
            (let [{:keys [asked value]} (eval-with-stdin "(read-line)" nil)]
              (is (= 0 asked))
              (is (= "\"a\"" value)))))))))

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

(deftest ^:skip-windows nrepl-port-file-test
  (when tu/native?
    (let [dir (fs/create-temp-dir)
          port-file (fs/file dir ".nrepl-port")
          proc (p/process {:dir (str dir) :err :inherit} (str (fs/absolutize "bb")) "nrepl-server" "0")]
      (try
        (testing "bb nrepl-server writes the port it listens on to .nrepl-port"
          (is (wait/wait-for-path (str port-file) {:timeout 10000}))
          (let [port (parse-long (str/trim (slurp port-file)))]
            (is (pos? port))
            (is (wait/wait-for-port "127.0.0.1" port {:timeout 10000}))))
        (finally
          (p/destroy proc)
          @proc))
      (testing "the port file is removed when the server exits"
        (is (not (fs/exists? port-file)))))))

(defn- await-idle-sessions
  "Waits up to 5 seconds until no nREPL session or track-state thread is running."
  []
  (loop [n 0]
    (when (and (< n 100)
               (some (fn [^Thread t]
                       (or (= "nREPL-track-state" (.getName t))
                           (and (.startsWith (.getName t) "nREPL-session")
                                (= Thread$State/RUNNABLE (.getState t)))))
                     (keys (Thread/getAllStackTraces))))
      (Thread/sleep 50)
      (recur (inc n)))))

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
          (do (eval-over-the-wire port "(babashka.nrepl.server/stop-server! user/server)")
              (await-idle-sessions))
          (some-> @proc p/destroy-tree))
        (fs/delete-if-exists file)))))

(defn- session-sender
  "Clones a session over `in` and `os` and returns a function sending an op
  map to it and returning the replies up to the one with status `until`,
  or \"done\" if absent."
  [in os]
  (bencode/write-bencode os {"op" "clone"})
  (let [session (:new-session (read-msg (bencode/read-bencode in)))
        id (atom 0)]
    (fn send
      ([m] (send m "done"))
      ([m until]
       (let [id (str (swap! id inc))]
         (bencode/write-bencode os (assoc m "session" session "id" id))
         (loop [replies []]
           (let [reply (read-reply in session id)
                 replies (conj replies reply)]
             (if (contains? (set (:status reply)) until)
               replies
               (recur replies)))))))))

(defn- with-session
  "Calls `f` with a function sending an op map to a fresh session on
  `port` and returning the replies to it."
  [port f]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (.setSoTimeout socket 60000)
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

(deftest nrepl-context-classloader-test
  (let [dir (fs/create-temp-dir)]
    (spit (str (fs/file dir "nrepl_cl_resource.txt")) "found")
    (with-bb-script 1675
      (str "(babashka.classpath/add-classpath " (pr-str (str dir)) ")
            (def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1675 :quiet true}))")
      (fn []
        (with-session 1675
          (fn [send]
            (testing "RT/baseLoader finds a classpath resource in an eval"
              (is (= "\"found\""
                     (some :value (send {"op" "eval" "code" "(slurp (.getResource (clojure.lang.RT/baseLoader) \"nrepl_cl_resource.txt\"))"})))))))))
    (fs/delete-tree dir)))

(deftest nrepl-with-session-classloader-test
  (testing "with-session-classloader returns the value of its body"
    (is (= ":ok" (str/trim (tu/bb nil "-e" "(require 'nrepl.misc) (prn (nrepl.misc/with-session-classloader :ok))"))))))

(defn- connected-repl
  "Runs a connected REPL with `input` and returns its output."
  [target input]
  (if tu/jvm?
    (let [os (java.io.StringWriter.)
          in (r/indexing-push-back-reader (r/push-back-reader (java.io.StringReader. input)))]
      (sci/with-bindings {sci/out os sci/err os sci/in in}
        (repl/start-connected-repl! (common/ctx) (nrepl-server/parse-connect target)))
      (str os))
    (let [res @(p/process ["./bb" "repl" "--connect" target] {:in input :out :string :err :string})]
      (str (:out res) (:err res)))))

(deftest ^:skip-windows nrepl-connect-repl-test
  (with-bb-script 1674
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1674 :quiet true}))"
    (fn []
      (let [out (connected-repl "1674" "(+ 1 2)\n(def x 10)\n(println :side-effect)\n(ns foo.bar)\n(inc x)\n:repl/quit")]
        (testing "displays server values, output and namespace in the prompt"
          (is (str/includes? out "user=> 3"))
          (is (str/includes? out "#'user/x"))
          (is (str/includes? out ":side-effect"))
          (is (str/includes? out "foo.bar=> ")))
        (testing "aliases and syntax-quote are the server's"
          (let [out (connected-repl "1674" "(require '[clojure.string :as str])\n::str/foo\n`str/join\n:repl/quit")]
            (is (str/includes? out ":clojure.string/foo"))
            (is (str/includes? out "clojure.string/join"))))
        (testing "a stray delimiter is reported once, the next form still runs"
          (let [out (connected-repl "1674" "(+ 1 2) )\n(+ 3 4)\n:repl/quit")]
            (is (str/includes? out "3"))
            (is (str/includes? out "7"))
            (is (= 1 (count (re-seq #"Unmatched delimiter" out))))))
        (testing "at EOF the complete forms run and an unfinished one is reported"
          (let [out (connected-repl "1674" "(println :ok) (")]
            (is (str/includes? out ":ok"))
            (is (str/includes? out "EOF"))))
        (testing "a tagged literal reaches the server's reader, a command keyword inside it too"
          (let [out (connected-repl "1674" "#my/tag :repl/quit\n(+ 40 2)\n:repl/quit")]
            (is (str/includes? out "42"))))
        (testing "the server's read-line gets the next line"
          (let [out (connected-repl "1674" "(read-line)\nhello\n:repl/quit")]
            (is (str/includes? out "\"hello\""))))
        (testing "displays the server exception type"
          (let [out (connected-repl "127.0.0.1:1674" "(/ 1 0)\n:repl/quit")]
            (is (str/includes? out "ArithmeticException"))))))))

(defn- scripted-line-reader
  "A LineReader answering readLine with `lines` in turn: a string is
  returned, :interrupt throws UserInterruptException, exhausted lines throw
  EndOfFileException."
  ^LineReader [lines]
  (let [remaining (atom lines)]
    (reify LineReader
      (^String readLine [_ ^String _prompt]
        (let [line (first @remaining)]
          (swap! remaining rest)
          (cond (nil? line) (throw (EndOfFileException.))
                (= :interrupt line) (throw (UserInterruptException. ""))
                :else line))))))

(deftest ^:skip-windows nrepl-connect-stdin-test
  ;; the terminal path, with mock readers, so the JVM route only
  (when tu/jvm?
    (with-bb-script 1678
      "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1678 :quiet true}))"
      (fn []
        (let [target {:host "127.0.0.1" :port 1678}
              client (repl/connect-client (common/ctx) target)
              os (java.io.StringWriter.)]
          (sci/with-bindings {sci/out os sci/err os}
            (try (repl/connected-repl-with-line-reader
                  (common/ctx) client target
                  (scripted-line-reader ["(read-line)" "(+ 1 2)" "(read-line)" "(read-line)" ":repl/quit"])
                  (scripted-line-reader [:interrupt "hello (" ""]))
                 (finally ((:close client)))))
          (let [out (str os)]
            (testing "Ctrl-C during the server's read-line interrupts the eval, the REPL goes on"
              (is (str/includes? out "Interrupted"))
              (is (str/includes? out "3"))
              (is (< (str/index-of out "Interrupted") (str/index-of out "3"))))
            (testing "stdin lines go through as typed, an empty line included"
              (is (str/includes? out "\"hello (\""))
              (is (str/includes? out "\"\"")))))))))

(deftest ^:skip-windows nrepl-eval-error-test
  (with-bb-script 1673
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1673 :quiet true}))"
    (fn []
      (with-session 1673
        (fn [send]
          (send {"op" "eval" "code" "(defn inner [x] (/ x 0)) (defn middle [x] (inner x)) (defn outer [x] (middle x))"})
          (testing "eval errors include the exception class and source location"
            (let [replies (send {"op" "eval" "code" "(outer 1)"})]
              (is (str/includes? (str/join (keep :err replies)) "java.lang.ArithmeticException: Divide by zero"))
              (is (str/includes? (str/join (keep :err replies)) "[at NO_SOURCE_PATH:1:"))
              (is (= "class java.lang.ArithmeticException" (some :ex replies)))))
          (testing "*e retains the original exception"
            (is (= ["[java.lang.ArithmeticException nil]"] (keep :value (send {"op" "eval" "code" "[(class *e) (ex-data *e)]"})))))
          (testing "analyze-last-stacktrace returns sci frames"
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
                          set)))
              (testing "all frames have function names, including top-level frames"
                (is (every? #(seq (bytes->str (get % "fn"))) (:stacktrace cause)))
                (is (some #(= "user/fn" (bytes->str (get % "name"))) (:stacktrace cause))))))
          (testing "a reader error names what is wrong, not the wrapper"
            (let [err (str/join (keep :err (send {"op" "eval" "code" "(comment {:a} 1)"})))]
              (is (str/includes? err "must contain an even number of forms"))))
          (testing "the file, line and column of the message reach the var"
            (send {"op" "eval" "code" "(defn located [])"
                   "file" "src/a/b.clj" "line" 42 "column" 10})
            (is (= ["[\"src/a/b.clj\" 42 10]"]
                   (keep :value (send {"op" "eval"
                                       "code" "((juxt :file :line :column) (meta #'located))"}))))))))))

(deftest ^:skip-windows nrepl-printing-test
  (with-bb-script 1674
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1674 :quiet true}))"
    (fn []
      (with-session 1674
        (fn [send]
          (testing "streamed printing keeps printed output out of the value"
            (let [replies (send {"op" "eval"
                                 "code" "(->> (range 2) (map println))"
                                 "nrepl.middleware.print/stream?" "1"})]
              (is (= ["0\n" "1\n"] (keep :out replies)))
              (is (= ["(nil nil)"] (keep :value replies)))))
          (testing "the session's print settings apply to the value"
            (send {"op" "eval" "code" "(set! *print-length* 3)"})
            (is (= ["(0 1 2 ...)"] (keep :value (send {"op" "eval" "code" "(range 10)"}))))))))))

(defn- decode [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (bytes->str k)) (decode v)])) x)
    (sequential? x) (mapv decode x)
    (bytes? x) (bytes->str x)
    :else x))

(deftest ^:skip-windows nrepl-cider-ops-test
  (with-bb-script 1671
    "(ns ct-demo (:require [clojure.test :refer [deftest is testing]]))
     (deftest passing (is (= 1 1)))
     (deftest failing (testing \"ctx\" (is (= 1 2) \"nope\")))
     (deftest erroring (is (= 1 (throw (ex-info \"boom\" {:a 1})))))
     (ns ct-fault (:require [clojure.test :refer [deftest is]]))
     (deftest faulting (throw (ex-info \"boom\" {})))
     (deftest double-message (is (= 1 2) 1.5))
     (ns ct-filter (:require [clojure.test :refer [deftest is]]))
     (deftest ^:smoke smoke (is true))
     (deftest ^:smoke ^:integration smoke-integration (is true))
     (deftest plain (is true))
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
          (testing "test-stacktrace starts in the test's namespace"
            (let [cause (first (send {"op" "test-stacktrace" "ns" "ct-demo" "var" "erroring" "index" 0}))]
              (is (= "boom" (bytes->str (:message cause))))
              (is (= "ct-demo" (bytes->str (get-in (first (:stacktrace cause)) ["ns"]))))))
          (testing "retest reruns only what failed"
            (let [summary (into {} (map (fn [[k v]] [(keyword (bytes->str k)) v])) (:summary (first (send {"op" "retest"}))))]
              (is (= 2 (:test summary)))
              (is (= 0 (:pass summary)))))
          (testing "test-var-query reports a throwing test and a double message"
            (let [results (decode (get (:results (first (send {"op" "test-var-query"
                                                               "var-query" {"ns-query" {"exactly" ["ct-fault"]}}})))
                                       "ct-fault"))]
              (is (= "true" (get-in results ["faulting" 0 "fault"])))
              (is (= "1.5" (get-in results ["double-message" 0 "message"])))))
          (testing "test with include smoke and exclude integration runs only smoke"
            (is (= ["smoke"] (keys (get (decode (:results (first (send {"op" "test" "ns" "ct-filter"
                                                                         "include" ["smoke"]
                                                                         "exclude" ["integration"]}))))
                                         "ct-filter")))))
          (testing "test-var-query with include-meta-key smoke and exclude-meta-key integration runs only smoke"
            (is (= ["smoke"] (keys (get (decode (:results (first (send {"op" "test-var-query"
                                                                         "var-query" {"ns-query" {"exactly" ["ct-filter"]}
                                                                                      "include-meta-key" ["smoke"]
                                                                                      "exclude-meta-key" ["integration"]}}))))
                                         "ct-filter")))))
          (testing "inspect-last-exception inspects the analyzed exception and its ex-data"
            (send {"op" "eval" "code" "(throw (ex-info \"boom\" {:a 1}))"})
            (send {"op" "analyze-last-stacktrace"})
            (let [cause (:value (first (send {"op" "inspect-last-exception" "index" 0})))
                  data (:value (first (send {"op" "inspect-last-exception" "index" 0 "ex-data" "true"})))]
              (is (str/includes? cause "boom"))
              (is (str/includes? data "\":a\""))
              (is (not (str/includes? data "sci/error")))))
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

(deftest ^:skip-windows nrepl-test-var-query-session-test
  (with-bb-script 1682
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1682 :quiet true}))"
    (fn []
      (testing "test-var-query for a var in an unloaded namespace replies namespace-not-found"
        (with-session 1682
          (fn [send]
            (is (some #{"namespace-not-found"}
                      (mapcat :status (send {"op" "test-var-query"
                                             "var-query" {"exactly" ["not-loaded.ns/t"]}}))))
            (is (= ["3"] (keep :value (send {"op" "eval" "code" "(+ 1 2)"})))))))
      (testing "test-var-query with search \"[\" replies error and keeps the session"
        (with-session 1682
          (fn [send]
            (is (some #{"error"}
                      (mapcat :status (send {"op" "test-var-query"
                                             "var-query" {"search" "["}}))))
            (is (= ["3"] (keep :value (send {"op" "eval" "code" "(+ 1 2)"})))))))
      (testing "test-var-query reports the file of the test after load-file of another file"
        (with-session 1682
          (fn [send]
            (let [dir (fs/create-temp-dir)
                  a (str (fs/path dir "file_a.clj"))
                  b (str (fs/path dir "file_b.clj"))]
              (spit a "(ns file-a\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest t1 (is (= 1 2)))\n")
              (spit b "(ns file-b)\n")
              (doseq [f [a b]]
                (send {"op" "load-file" "file" (slurp f) "file-name" (str (fs/file-name f)) "file-path" f}))
              (let [result (get-in (decode (:results (first (send {"op" "test-var-query"
                                                                   "var-query" {"exactly" ["file-a/t1"]}}))))
                                   ["file-a" "t1" 0])]
                (is (= a (get result "file")))
                (is (= 4 (get result "line"))))
              (fs/delete-tree dir))))))))

(deftest ^:skip-windows nrepl-test-var-query-project-test
  (with-bb-script 1681
    "(babashka.classpath/add-classpath \"test-resources/nrepl_project\")
     (ns scratch.other-test (:require [clojure.test :refer [deftest is]]))
     (deftest not-mine (is true))
     (ns user)
     (def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1681 :quiet true}))"
    (fn []
      (with-session 1681
        (fn [send]
          (testing "test-var-query with project? and load-project-ns? runs the project's tests only"
            (let [results (decode (:results (first (send {"op" "test-var-query"
                                                          "var-query" {"ns-query" {"project?" "true"
                                                                                   "load-project-ns?" "true"}}}))))]
              (is (= #{"mine" "smoke" "smoke-integration"} (set (keys (get results "proj.a-test")))))
              (is (= ["in-bb-file"] (keys (get results "proj.b-test"))))
              (is (not (contains? results "scratch.other-test")))))
          (testing "test-var-query with project? 1 runs the project's tests only"
            (let [results (decode (:results (first (send {"op" "test-var-query"
                                                          "var-query" {"ns-query" {"project?" 1}}}))))]
              (is (contains? results "proj.a-test"))
              (is (not (contains? results "scratch.other-test")))))
          (testing "test-all with include smoke and exclude integration runs only smoke"
            (let [results (decode (:results (first (send {"op" "test-all"
                                                          "include" ["smoke"]
                                                          "exclude" ["integration"]}))))]
              (is (= ["smoke"] (keys (get results "proj.a-test"))))))
          (testing "test-all runs the project's tests only"
            (let [results (decode (:results (first (send {"op" "test-all"}))))]
              (is (contains? results "proj.a-test"))
              (is (not (contains? results "scratch.other-test"))))))))))

(deftest ^:skip-windows nrepl-doc-test
  (with-bb-script 1680
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1680 :quiet true}))"
    (fn []
      (with-session 1680
        (fn [send]
          (testing "info on do returns the special form doc"
            (let [reply (decode (first (send {"op" "info" "sym" "do" "ns" "user"})))]
              (is (= "clojure.core" (get reply "ns")))
              (is (= "true" (get reply "special-form")))
              (is (= "(do exprs*)" (get reply "forms-str")))
              (is (str/starts-with? (get reply "doc") "Evaluates the expressions"))))
          (testing "info on . returns the java_interop url"
            (is (= "https://clojure.org/java_interop#dot"
                   (get (decode (first (send {"op" "info" "sym" "." "ns" "user"}))) "url"))))
          (testing "info on catch returns the doc of try under the name catch"
            (let [reply (decode (first (send {"op" "info" "sym" "catch" "ns" "user"})))]
              (is (= "catch" (get reply "name")))
              (is (str/includes? (get reply "forms-str") "(try"))))
          (testing "info on & returns the doc of clojure.core/fn"
            (let [reply (decode (first (send {"op" "info" "sym" "&" "ns" "user"})))]
              (is (= ["clojure.core" "fn"] [(get reply "ns") (get reply "name")]))
              (is (string? (get reply "doc")))))
          (testing "info on let* returns no special-form"
            (is (nil? (get (decode (first (send {"op" "info" "sym" "let*" "ns" "user"}))) "special-form"))))
          (testing "info on nope returns status no-info"
            (is (some #{"no-info"} (get (decode (first (send {"op" "info" "sym" "nope" "ns" "user"}))) "status"))))
          (testing "eldoc on when returns type macro"
            (is (= "macro" (get (decode (first (send {"op" "eldoc" "sym" "when" "ns" "user"}))) "type"))))
          (testing "info on when returns macro true and added 1.0"
            (let [reply (decode (first (send {"op" "info" "sym" "when" "ns" "user"})))]
              (is (= ["true" "1.0"] [(get reply "macro") (get reply "added")]))))
          (testing "info on replicate returns deprecated 1.3"
            (is (= "1.3" (get (decode (first (send {"op" "info" "sym" "replicate" "ns" "user"}))) "deprecated"))))
          (testing "info on a var from load-file returns its file, line and column"
            (let [dir (fs/create-temp-dir)
                  f (str (fs/path dir "info_loc.clj"))]
              (spit f "(ns info-loc)\n\n(defn hello [x]\n  (str \"hi \" x))\n")
              (send {"op" "load-file" "file" (slurp f) "file-name" "info_loc.clj" "file-path" f})
              (let [reply (decode (first (send {"op" "info" "sym" "info-loc/hello" "ns" "user"})))]
                (is (= (str "file:" f) (get reply "file")))
                (is (= [3 1] [(get reply "line") (get reply "column")])))
              (fs/delete-tree dir)))
          (testing "info on clojure.core/map returns clojure/core.clj with a line and column"
            (let [reply (decode (first (send {"op" "info" "sym" "clojure.core/map" "ns" "user"})))]
              (is (= "clojure/core.clj" (get reply "file")))
              (is (pos-int? (get reply "line")))
              (is (pos-int? (get reply "column")))))
          (testing "eldoc on do returns the special form's forms"
            (let [reply (decode (first (send {"op" "eldoc" "sym" "do" "ns" "user"})))]
              (is (= "special-form" (get reply "type")))
              (is (= [["do" "exprs*"]] (get reply "eldoc")))))
          (send {"op" "eval" "code" "(reset! orchard.clojuredocs/cache
                                       {:clojure.core/cond {:ns \"clojure.core\" :name \"cond\"
                                                            :examples [\"(cond true 1)\"]
                                                            :see-alsos [:clojure.core/condp]}})"})
          (testing "info on cond returns see-also from the ClojureDocs export"
            (is (= ["clojure.core/condp"] (get (decode (first (send {"op" "info" "sym" "cond" "ns" "user"}))) "see-also"))))
          (testing "cider/clojuredocs-lookup on cond returns its examples"
            (let [reply (decode (first (send {"op" "cider/clojuredocs-lookup" "sym" "cond" "ns" "user"})))]
              (is (= ["(cond true 1)"] (get-in reply ["clojuredocs" "examples"])))
              (is (= ["clojure.core/condp"] (get-in reply ["clojuredocs" "see-alsos"])))))
          (testing "cider/clojuredocs-lookup on inc returns no-doc"
            (is (contains? (set (:status (first (send {"op" "cider/clojuredocs-lookup" "sym" "inc" "ns" "user"})))) "no-doc")))
          (testing "cider/clojuredocs-lookup without ns returns no-doc"
            (is (contains? (set (:status (first (send {"op" "cider/clojuredocs-lookup" "sym" "cond"})))) "no-doc")))
          (testing "describe lists cider/clojuredocs-lookup"
            (is (contains? (get (decode (first (send {"op" "describe"}))) "ops") "cider/clojuredocs-lookup"))))))))

(defn- disconnect-after-eval [port]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (.setSoTimeout socket 60000)
    ((session-sender in os) {"op" "eval" "code" "1"})))

(deftest ^:skip-windows nrepl-track-state-disconnect-test
  (let [script "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1683 :quiet true}))"]
    (testing "a client disconnect after done leaves nothing on stderr"
      (if tu/jvm?
        (let [uncaught (atom [])
              previous (Thread/getDefaultUncaughtExceptionHandler)]
          (Thread/setDefaultUncaughtExceptionHandler
           (reify Thread$UncaughtExceptionHandler
             (uncaughtException [_ t _] (swap! uncaught conj (.getName t)))))
          (try
            (with-bb-script 1683 script #(disconnect-after-eval 1683))
            (finally (Thread/setDefaultUncaughtExceptionHandler previous)))
          (is (not-any? #{"nREPL-track-state"} @uncaught)))
        (let [file (str (fs/create-temp-file {:suffix ".clj"}))
              _ (spit file script)
              proc (p/process ["./bb" file] {:err :string})]
          (wait/wait-for-port "127.0.0.1" 1683 {:timeout 10000})
          (disconnect-after-eval 1683)
          (Thread/sleep 1000)
          (p/destroy-tree proc)
          (is (not (str/includes? (:err @proc) "nREPL-track-state")))
          (fs/delete-if-exists file))))))

(deftest ^:skip-windows nrepl-session-after-disconnect-test
  (with-bb-script 1684
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1684 :quiet true}))"
    (fn []
      (let [session (with-open [socket (java.net.Socket. "127.0.0.1" 1684)
                                in (java.io.PushbackInputStream. (.getInputStream socket))
                                os (.getOutputStream socket)]
                      (bencode/write-bencode os {"op" "clone"})
                      (let [session (:new-session (read-msg (bencode/read-bencode in)))]
                        (bencode/write-bencode os {"op" "eval" "code" "(Thread/sleep 500)" "session" session "id" "1"})
                        session))]
        (Thread/sleep 1000)
        (with-open [socket (java.net.Socket. "127.0.0.1" 1684)
                    in (java.io.PushbackInputStream. (.getInputStream socket))
                    os (.getOutputStream socket)]
          (.setSoTimeout socket 10000)
          (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2)" "session" session "id" "2"})
          (testing "a session evaluates after its client disconnects during an eval"
            (is (= "3" (:value (read-reply in session "2"))))))))))

(deftest ^:skip-windows nrepl-message-without-op-test
  (with-bb-script 1685
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1685 :quiet true}))"
    (fn []
      (with-open [socket (java.net.Socket. "127.0.0.1" 1685)
                  in (java.io.PushbackInputStream. (.getInputStream socket))
                  os (.getOutputStream socket)]
        (.setSoTimeout socket 10000)
        (bencode/write-bencode os {"id" "1"})
        (testing "a message without an op gets unknown-op"
          (is (contains? (set (:status (read-msg (bencode/read-bencode in)))) "unknown-op")))))))

(deftest nrepl-java-info-test
  (with-bb-script 1687
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1687 :quiet true}))"
    (fn []
      (with-session 1687
        (fn [send]
          (let [reply (fn [m] (decode (first (send (assoc m "ns" "user")))))]
            (testing "info on String returns the class, its superclass and its javadoc"
              (let [r (reply {"op" "info" "sym" "String"})]
                (is (= ["java.lang.String" "java.lang.Object"] [(get r "class") (get r "super")]))
                (is (str/ends-with? (get r "javadoc") "java.base/java/lang/String.html"))))
            (testing "info on String/valueOf returns static arglists and the return type"
              (let [r (reply {"op" "info" "sym" "String/valueOf"})]
                (is (= "valueOf" (get r "member")))
                (is (some #{"static"} (get r "modifiers")))
                (is (str/includes? (get r "arglists-str") "[int]"))
                (is (= "java.lang.String" (get r "returns")))))
            (testing "info with class String and member substring returns arglists with this"
              (is (= "[this int]\n[this int int]"
                     (get (reply {"op" "info" "class" "String" "member" "substring"}) "arglists-str"))))
            (testing "info on Integer/MAX_VALUE returns the field type int"
              (is (= "int" (get (reply {"op" "info" "sym" "Integer/MAX_VALUE"}) "type"))))
            (testing "info on java.util.ArrayList/.stream returns the default method of Collection"
              (is (= "stream" (get (reply {"op" "info" "sym" "java.util.ArrayList/.stream"}) "member"))))
            (testing "info on .length returns candidates for String and File"
              (let [candidates (get (reply {"op" "info" "sym" ".length"}) "candidates")]
                (is (contains? candidates "java.lang.String"))
                (is (contains? candidates "java.io.File"))))
            (testing "eldoc on String/.length returns type function and arglist [this]"
              (let [r (reply {"op" "eldoc" "sym" "String/.length"})]
                (is (= ["function" [["this"]]] [(get r "type") (get r "eldoc")]))))))))))

(deftest nrepl-cider-op-names-test
  (with-bb-script 1686
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1686 :quiet true}))"
    (fn []
      (with-session 1686
        (fn [send]
          (let [reply #(decode (first (send %)))]
            (testing "cider/info returns the name of map"
              (is (= "map" (get (reply {"op" "cider/info" "sym" "map" "ns" "user"}) "name"))))
            (testing "cider/eldoc returns the arglists of inc"
              (is (= [["x"]] (get (reply {"op" "cider/eldoc" "sym" "inc" "ns" "user"}) "eldoc"))))
            (testing "cider/complete with prefix mapv completes mapv"
              (is (some #{"mapv"} (map #(get % "candidate")
                                       (get (reply {"op" "cider/complete" "prefix" "mapv" "ns" "user"}) "completions")))))
            (testing "cider/ns-list with filter-regexps leaves out clojure namespaces"
              (let [nss (get (reply {"op" "cider/ns-list" "filter-regexps" ["^clojure"]}) "ns-list")]
                (is (some #{"user"} nss))
                (is (not-any? #(str/starts-with? % "clojure") nss))))
            (testing "cider/classpath returns a list"
              (is (sequential? (get (reply {"op" "cider/classpath"}) "classpath"))))
            (testing "describe lists the cider/ and the bare names"
              (let [ops (set (keys (get (reply {"op" "describe"}) "ops")))]
                (doseq [op ["info" "eldoc" "complete" "ns-list" "classpath"
                            "test-var-query" "analyze-last-stacktrace" "inspect-push"]]
                  (is (contains? ops op))
                  (is (contains? ops (str "cider/" op))))))))))))

(deftest ^:skip-windows nrepl-track-state-test
  (with-bb-script 1679
    "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1679 :quiet true}))"
    (fn []
      (with-session 1679
        (fn [send]
          (let [replies (send {"op" "eval" "code" "(ns ts-demo (:require [clojure.string :as s]))
                                                   (defn f [] 1) (defmacro m [] 1) (defn ^:deprecated old [] 1)
                                                   #dbg (defn g [x] (inc x))"}
                              "state")
                state (decode (last replies))]
            (testing "an eval replies with the changed namespaces after done"
              (is (contains? (get state "changed-namespaces") "ts-demo"))
              (is (= ["done"] (:status (last (butlast replies)))))))
          (with-session 1679
            (fn [send]
              (let [state (decode (first (send {"op" "cider/get-state"})))
                    ns-state (get-in state ["changed-namespaces" "ts-demo"])]
                (testing "cider/get-state reports the aliases and vars of a namespace"
                  (is (= "clj" (get state "repl-type")))
                  (is (= {"s" "clojure.string"} (get ns-state "aliases")))
                  (is (= {"fn" "true"} (get-in ns-state ["interns" "f"])))
                  (is (= {"macro" "true"} (get-in ns-state ["interns" "m"])))
                  (is (= {"fn" "true" "deprecated" "true"} (get-in ns-state ["interns" "old"]))))
                (testing "cider/get-state reports clojure.core"
                  (is (= {"macro" "true"} (get-in state ["changed-namespaces" "clojure.core" "interns" "when"]))))
                (testing "an instrumented var carries :cider/instrumented"
                  (is (= "\"breakpoint-if-interesting\"" (get-in ns-state ["interns" "g" "cider/instrumented"]))))
                (testing "a second cider/get-state reports no changed namespaces"
                  (is (empty? (get (decode (first (send {"op" "cider/get-state"}))) "changed-namespaces"))))
                (testing "an alias of a removed namespace stays in the state"
                  (send {"op" "eval" "code" "(ns ts-gone) (ns ts-aliasing (:require [ts-gone :as g])) (remove-ns 'ts-gone)"})
                  (let [replies (send {"op" "eval" "code" "(in-ns 'ts-aliasing) (def after-remove 1)"} "state")
                        state (decode (last replies))]
                    (is (= {"g" "ts-gone"} (get-in state ["changed-namespaces" "ts-aliasing" "aliases"])))
                    (is (contains? (get-in state ["changed-namespaces" "ts-aliasing" "interns"]) "after-remove"))))))))))))

(defn- sessionless-eval
  "Evaluates code on the server at port without cloning a session.
  Returns the value."
  [port code]
  (with-open [socket (doto (java.net.Socket. "127.0.0.1" (int port))
                       (.setSoTimeout 30000))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "eval" "id" "s" "code" code})
    (loop []
      (let [msg (read-msg (bencode/read-bencode in))]
        (if (and (= "s" (:id msg)) (:value msg))
          (:value msg)
          (recur))))))

(defn- debug-eval
  "Evaluates code in a fresh session and closes it afterwards.
  Answers each breakpoint with the next of inputs.
  Initializes the debugger first if inputs is not empty.
  Sends the eval once the debugger replies to this session.
  Returns the breakpoints and the eval replies."
  [port code inputs]
  (with-open [socket (doto (java.net.Socket. "127.0.0.1" (int port))
                       (.setSoTimeout 30000))
              in (java.io.PushbackInputStream. (.getInputStream socket))
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "clone"})
    (let [session (:new-session (read-msg (bencode/read-bencode in)))]
      (when (seq inputs)
        (let [id (str "dbg-" (random-uuid))
              deadline (+ (System/currentTimeMillis) 30000)]
          (bencode/write-bencode os {"op" "init-debugger" "session" session "id" id})
          (while (and (not= "true" (sessionless-eval port (format "(= %s (:id @cider.nrepl.middleware.debug/debugger-message))" (pr-str id))))
                      (< (System/currentTimeMillis) deadline))
            (Thread/sleep 10))))
      (bencode/write-bencode os {"op" "eval" "session" session "id" "e" "code" code})
      (loop [inputs inputs breaks [] replies []]
        (let [msg (read-msg (bencode/read-bencode in))]
          (cond
            (contains? (set (:status msg)) "need-debug-input")
            (do (bencode/write-bencode os {"op" "debug-input" "session" session "id" (str (random-uuid))
                                           "key" (:key msg) "input" (first inputs)})
                (recur (rest inputs) (conj breaks (select-keys msg [:coor :debug-value :code])) replies))

            (= "e" (:id msg))
            (let [replies (conj replies msg)]
              (if (contains? (set (:status msg)) "done")
                (do (bencode/write-bencode os {"op" "close" "session" session "id" "close"})
                    (read-reply in session "close")
                    {:breaks breaks :replies replies})
                (recur inputs breaks replies)))

            :else (recur inputs breaks replies)))))))

(deftest nrepl-debugger-test
  (let [dir (fs/create-temp-dir)
        file (str (fs/file dir "dbg_demo.clj"))
        tagged (str (fs/file dir "dbg_tagged.clj"))]
    (spit file "(ns dbg-demo)\n\n(defn bar [x]\n  (* 10 x))\n\n(defn foo [a]\n  (inc (bar a)))\n")
    (spit tagged "(ns dbg-tagged)\n\n(defn f [a]\n  #dbg (let [b (* 10 a)] (+ a b)))\n")
    (try
      (with-bb-script 1676
        "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1676 :quiet true}))"
        (fn []
          (testing "the debugger loads on the first clone"
            (is (= "nil" (sessionless-eval 1676 "(find-ns 'cider.nrepl.middleware.debug)")))
            (is (= "true" (some :value (:replies (debug-eval 1676 "(some? (find-ns 'cider.nrepl.middleware.debug))" []))))))
          (testing "#dbg stops at each subform with its value"
            (let [{:keys [breaks replies]} (debug-eval 1676 "#dbg (let [x 1 y (inc x)] (+ x y))"
                                                       [":next" ":next" ":eval" "(* y 100)" ":continue"])]
              (is (= [[1 3 1] [1 3] [2 1] [2 1] [2 1]] (map :coor breaks)))
              (is (= ["1" "2" "1" "1" "200"] (map :debug-value breaks)))
              (is (= "3" (some :value replies)))))
          (testing ":in steps into a var loaded from a file"
            (debug-eval 1676 (str "(load-file " (pr-str file) ")") [])
            (let [{:keys [breaks replies]} (debug-eval 1676 "#dbg (let [z 2] (dbg-demo/foo z))"
                                                       [":in" ":in" ":quit"])]
              (is (str/starts-with? (:code (nth breaks 1)) "(defn foo"))
              (is (str/starts-with? (:code (nth breaks 2)) "(defn bar"))
              (testing ":quit ends the eval without an error"
                (is (= "QUIT" (some :value replies)))
                (is (not-any? :err replies)))))
          (testing "a file with #dbg loads once the debugger has loaded"
            (is (= "11" (last (keep :value (:replies (debug-eval 1676 (str "(load-file " (pr-str tagged) ") (dbg-tagged/f 1)") [])))))))))
      (finally
        (fs/delete-tree dir)))))

(deftest nrepl-debugger-first-eval-test
  (let [dir (fs/create-temp-dir)
        tagged (str (fs/file dir "dbg_first.clj"))]
    (spit tagged "(ns dbg-first)\n\n(defn f [a]\n  #dbg (let [b (* 10 a)] (+ a b)))\n")
    (try
      (with-bb-script 1677
        "(def server (babashka.nrepl.server/start-server! {:host \"127.0.0.1\" :port 1677 :quiet true}))"
        (fn []
          (testing "an eval sent right after init-debugger reads #dbg"
            (is (= "11" (last (keep :value (:replies (debug-eval 1677 (str "(load-file " (pr-str tagged) ") (dbg-first/f 1)") [":continue"])))))))))
      (finally
        (fs/delete-tree dir)))))

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
