(ns nrepl.core-test
  (:refer-clojure :exclude [requiring-resolve])
  ;; BB-TEST-PATCH type hints naming classes the image does not expose are dropped
  (:require
   [clojure.java.io :as io]
   [clojure.main]
   [clojure.set :as set]
   [clojure.spec.alpha]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl :refer [client
                                 client-session
                                 code
                                 combine-responses
                                 connect
                                 message
                                 new-session
                                 read-response-value
                                 response-seq
                                 response-values
                                 url-connect]]
   [nrepl.ack :as ack]
   [nrepl.middleware.caught :as middleware.caught]
   [nrepl.middleware.print :as middleware.print]
   [nrepl.middleware.session :as session]
   [nrepl.misc :refer [uuid requiring-resolve]]
   [nrepl.server :as server]
   [nrepl.test-helpers :as th :refer [is+]]
   [nrepl.util.threading :as threading]
   [nrepl.transport :as transport])
  (:import
   (java.io File Writer)
   java.net.SocketException))

;; BB-TEST-PATCH nrepl.spec is not shipped by babashka; the lib tests driver
;; loads it from the checkout before requiring this namespace.

;; BB-TEST-PATCH a sci defrecord cannot implement Closeable, so the server is
;; closed explicitly instead of through with-open.
(defmacro with-server [[sym expr] & body]
  `(let [~sym ~expr] (try ~@body (finally (server/stop-server ~sym)))))

(deftest version-sanity-check
  (is (let [v (System/getenv "CLOJURE_VERSION")]
        (println "Running on Clojure" (clojure-version) ", expected:" v)
        (or (nil? v) (.startsWith ^String (clojure-version) v)))))

(def transport-fn->protocol
  "Add your transport-fn var here so it can be tested"
  {#'transport/bencode "nrepl"
   #'transport/edn "nrepl+edn"})

(def ^File project-base-dir (File. (System/getProperty "nrepl.basedir" ".")))

(def ^:dynamic *server* nil)
(def ^:dynamic *transport-fn* nil)
(def ^:dynamic *sessions-to-close*)

(defn start-server-for-transport-fn
  [transport-fn f]
  (with-server [server (server/start-server :transport-fn transport-fn)]
    (binding [*server* server
              *transport-fn* transport-fn]
      (testing (format "transport:%s -" (-> transport-fn meta :name))
        (f))
      (set! *print-length* nil)
      (set! *print-level* nil))))

(def transport-fns
  (keys transport-fn->protocol))

(defn repl-server-fixture
  "This iterates through each transport being tested, starts a server,
   runs the test against that server, then cleans up all sessions."
  [f]
  (doseq [transport-fn transport-fns]
    (binding [*sessions-to-close* #{}]
      (start-server-for-transport-fn transport-fn f)
      (run! session/close-session *sessions-to-close*))))

(use-fixtures :each repl-server-fixture)

(defn closeable-session [client]
  (let [session (client-session client)]
    (set! *sessions-to-close* (conj *sessions-to-close* session))
    session))

(defmacro with-repl-server [& body]
  `(with-open [transport# (connect :port (:port *server*)
                                   :transport-fn *transport-fn*)]
     (let [~'transport transport#
           ~'client (client transport# Long/MAX_VALUE)
           ~'session (closeable-session ~'client)
           ~'timeout-client (client transport# 1000)
           ~'timeout-session (closeable-session ~'timeout-client)
           ~'repl-eval #(message % {:op "eval" :code %2})
           ~'repl-values (comp response-values ~'repl-eval)]
       ~@body)))

(defmacro def-repl-test
  {:style/indent 1}
  [name & body]
  `(deftest ~name
     (with-repl-server ~@body)))

(defn- strict-transport? []
  (= *transport-fn* #'transport/edn))

(defn- check-response-format
  "Check response against nrepl.spec."
  [resp]
  (clojure.spec.alpha/assert* :nrepl.spec/message resp)
  resp)

(defn clean-response
  "Cleans a response to help testing.

  This manually coerces bencode responses to (close) to what the raw EDN
  response is, so we can standardise testing around the richer format. It
  retains strictness on EDN transports.

  - de-identifies the response
  - ensures the status to a set of keywords
  - turn the content of truncated-keys to keywords"
  [resp]
  (let [de-identify
        (fn [resp]
          (dissoc resp :id :session))
        normalize-status
        (fn [resp]
          (if-let [status (:status resp)]
            (assoc resp :status (set (map keyword status)))
            resp))
        ;; This is a good example of a middleware details that's showing through
        keywordize-truncated-keys
        (fn [resp]
          (if (contains? resp ::middleware.print/truncated-keys)
            (update resp ::middleware.print/truncated-keys #(mapv keyword %))
            resp))]
    (cond-> resp
      true                      de-identify
      (not (strict-transport?)) normalize-status
      (not (strict-transport?)) keywordize-truncated-keys
      (strict-transport?)       check-response-format)))

(def-repl-test eval-literals
  (are [literal] (= (binding [*ns* (find-ns 'user)] ; needed for the ::keyword
                      (-> literal read-string eval list))
                    (repl-values client literal))
    "5"
    "0xff"
    "5.1"
    "-2e12"
    "1/4"
    "'symbol"
    "'namespace/symbol"
    ":keyword"
    "::local-ns-keyword"
    ":other.ns/keyword"
    "\"string\""
    "\"string\\nwith\\r\\nlinebreaks\""
    "'(1 2 3)"
    "[1 2 3]"
    "{1 2 3 4}"
    "#{1 2 3 4}")

  (is (= (->> "#\"regex\"" read-string eval list (map str))
         (->> "#\"regex\"" (repl-values client) (map str)))))

(def-repl-test simple-expressions
  (are [expr] (= [(eval expr)] (repl-values client (pr-str expr)))
    '(range 40)
    '(apply + (range 100))))

(def-repl-test defining-fns
  (repl-values client "(defn x [] 6)")
  (is (= [6] (repl-values client "(x)"))))

(defn- dumb-alternative-eval
  [form]
  (let [result (eval form)]
    (if (number? result)
      (- result)
      result)))

(def-repl-test use-alternative-eval-fn
  (is+ {:value ["-124750"]}
       (-> (message timeout-client {:op "eval" :eval "nrepl.core-test/dumb-alternative-eval"
                                    :code "(reduce + (range 500))"})
           combine-responses)))

(def-repl-test source-tracking-eval
  (let [sym (name (gensym))
        request {:op "eval" :ns "user" :code (format "(def %s 1000)" sym)
                 :file "test.clj" :line 42 :column 10}]
    (doall (message timeout-client request))
    (is+ {:file "test.clj", :line 42, :column 10}
         (meta (resolve (symbol "user" sym))))))

(def-repl-test no-code
  (is+ {:status #{:error :no-code :done}}
       (-> (message timeout-client {:op "eval"})
           combine-responses
           clean-response)))

(def-repl-test unknown-op
  (is+ {:op "abc" :status #{:error :unknown-op :done}}
       (-> (message timeout-client {:op "abc"})
           combine-responses
           clean-response)))

(def-repl-test session-lifecycle
  (is+ {:status #{:error :unknown-session :done}}
       (-> (message timeout-client {:session "00000000-0000-0000-0000-000000000000"})
           combine-responses
           clean-response))
  (let [session-id (new-session timeout-client)
        session-alive? #(contains? (-> (message timeout-client {:op "ls-sessions"})
                                       combine-responses
                                       :sessions
                                       set)
                                   session-id)]
    (is session-id)
    (is (session-alive?))
    (is+ {:status #{:done :session-closed}}
         (-> (message timeout-client {:op "close" :session session-id})
             combine-responses
             clean-response))
    (is (not (session-alive?)))))

(def-repl-test separate-value-from-*out*
  (is+ {:value [nil] :out (th/newline->sys "5\n")}
       (-> (map read-response-value (repl-eval client "(println 5)"))
           combine-responses)))

(def-repl-test sessionless-*out*
  (is+ {:out (th/newline->sys "5\n:foo\n")}
       (-> (repl-eval client "(println 5)(println :foo)")
           combine-responses)))

(def-repl-test session-*out*
  (is+ {:out (th/newline->sys "5\n:foo\n")}
       (-> (repl-eval session "(println 5)(println :foo)")
           combine-responses)))

(def-repl-test error-on-lazy-seq-with-side-effects
  (let [expression '(let [foo (fn [] (map (fn [x]
                                            (println x)
                                            (throw (Exception. "oops")))
                                          [1 2 3]))]
                      (foo))]
    (is+ {:out (th/newline->sys "1\n")
          :err #"oops"}
         (-> (repl-eval session (pr-str expression))
             combine-responses))))

(def-repl-test cross-transport-*out*
  (let [sid (-> session meta ::nrepl/taking-until :session)]
    (with-open [transport2 (connect :port (:port *server*)
                                    :transport-fn *transport-fn*)]
      (transport/send transport2 {:op "eval" :code "(println :foo)"
                                  "session" sid})
      (is (= [{:out (th/newline->sys ":foo\n")}
              {:ns "user" :value "nil"}
              {:status #{:done}}]
             (->> (repeatedly #(transport/recv transport2 100))
                  (take-while identity)
                  (mapv clean-response)))))))

(def-repl-test streaming-out
  (is (= (for [x (range 10)]
           (str x th/sys-newline))
         (->> (repl-eval client "(dotimes [x 10] (println x))")
              (map :out)
              (remove nil?)))))

(def-repl-test session-*out*-writer-length-translation
  (is+ {:out (th/newline->sys "#inst \"2013-02-11T12:13:44.000+00:00\"\n")}
       (-> (repl-eval session
                      (code (println (doto (java.util.GregorianCalendar. 2013 1 11 12 13 44)
                                       (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))))))
           combine-responses)))

(def-repl-test streaming-out-without-explicit-flushing
  (is (= ["(0 1 "
          "2 3 4"
          " 5 6 "
          "7 8 9"
          " 10)"]
         ;; new session
         (->> (message client {:op "eval" :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?))
         ;; existing session
         (->> (message session {:op "eval" :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?)))))

(def-repl-test reader-conditional-option
  (is+ {:value ["#?(:clj (+ 1 2) :cljs (+ 2 3))"]}
       (->> (message client {:op "eval" :read-cond :preserve
                             :code "#?(:clj (+ 1 2) :cljs (+ 2 3))"})
            combine-responses))
  (is+ {:value ["3"]}
       (->> (message client {:op "eval" :code "#?(:clj (+ 1 2) :cljs (+ 2 3))"})
            combine-responses)))

(def-repl-test ensure-whitespace-prints
  (is+ {:out (str " \t \n \f " th/sys-newline)}
       (->> (repl-eval client "(println \" \t \n \f \")")
            combine-responses)))

(def-repl-test streamed-printing
  (testing "multiple forms"
    (let [responses (->> (message client {:op "eval"
                                          :code (code (range 10)
                                                      (range 10))
                                          ::middleware.print/stream? 1})
                         (mapv clean-response))]
      (is (= [{:value "(0 1 2 3 4 5 6 7 8 9)"}
              {:ns "user"}
              {:value "(0 1 2 3 4 5 6 7 8 9)"}
              {:ns "user"}
              {:status #{:done}}]
             responses))))

  (testing "*out* still handled correctly"
    (let [responses (->> (message client {:op "eval"
                                          :code (code (->> (range 2)
                                                           (map println)))
                                          ::middleware.print/stream? 1})
                         (mapv clean-response))]
      (is (= [{:out (th/newline->sys "0\n")}
              {:out (th/newline->sys "1\n")}
              {:value "(nil nil)"}
              {:ns "user"}
              {:status #{:done}}]
             responses))))

  ;; Making the buffer size large expose the odd of interrupting half way through
  ;; a message being written, which was the cause of https://github.com/nrepl/nrepl/issues/132
  (testing "interruptible"
    (let [eval-responses (->> (message session {:op "eval"
                                                :code (code (range))
                                                ::middleware.print/stream? 1
                                                ::middleware.print/buffer-size 100000})
                              (map clean-response))
;; BB-TEST-PATCH wait for the first chunk instead of guessing how long the
          ;; print buffer takes to fill, which loses the race on a slow machine
          _ (first eval-responses)
          interrupt-responses (->> (message session {:op "interrupt"})
                                   (mapv clean-response))]
      ;; check the interrupt succeeded first; otherwise eval-responses will not terminate
      (is (= [{:status #{:done}}] interrupt-responses))
      (is+ {:value #"^\(0 1 2 3"} (first eval-responses))
      (is (= {:status #{:done :interrupted}} (last eval-responses))))))

(def-repl-test session-return-recall
  (testing "sessions persist across connections"
    (dorun (repl-values session (code
                                 (apply + (range 6))
                                 (str 12 \c)
                                 (keyword "hello"))))
    (with-open [separate-connection (connect :port (:port *server*)
                                             :transport-fn *transport-fn*)]
      (let [history [[15 "12c" :hello]]
            sid (-> session meta :nrepl.core/taking-until :session)
            sc-session (-> separate-connection
                           (nrepl/client 1000)
                           (nrepl/client-session :session sid))]
        (is (= history (repl-values sc-session "[*3 *2 *1]")))
        (is (= history (repl-values sc-session "*1"))))))

  (testing "without a session id, REPL-bound vars like *1 have default values"
    (is (= [nil] (repl-values client "*1")))))

(def-repl-test session-set!
  (dorun (repl-eval session (code
                             (set! *compile-path* "badpath")
                             (set! *warn-on-reflection* true))))
  (is (= [["badpath" true]] (repl-values session (code [*compile-path* *warn-on-reflection*])))))

(def-repl-test exceptions
  (is+ {:status #{:eval-error :done}
        :value  mc/absent
        :err    #"bad, bad code"}
       (-> session
           (repl-eval "(throw (Exception. \"bad, bad code\"))")
           combine-responses
           clean-response))
  (is (= [true] (repl-values session "(.contains (str *e) \"bad, bad code\")"))))

(def-repl-test multiple-expressions-return
  (is (= [5 18] (repl-values session "5 (/ 5 0) (+ 5 6 7)"))))

(def-repl-test return-on-incomplete-expr
  (is+ {:status #{:done :eval-error}
        :value mc/absent}
       (-> (repl-eval session "(missing paren")
           combine-responses
           clean-response))
  (is+ [#"EOF while reading"]
       (repl-values session "(-> *e Throwable->map :cause)")))

(def-repl-test switch-ns
  (is+ {:ns "otherns"}
       (-> (repl-eval session "(ns otherns) (defn function [] 12)")
           combine-responses))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)"))))

(def-repl-test switch-ns-2
  (is+ {:ns "otherns"}
       (-> (repl-eval session (code
                               (ns otherns)
                               (defn function [] 12)))
           combine-responses))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)")))
  (is+ {:ns "user"} (-> (repl-eval session "nil") combine-responses)))

(def-repl-test explicit-ns
  (is+ {:ns "user"} (-> (repl-eval session "nil") combine-responses))
  (is+ {:ns "baz"} (-> (repl-eval session (code
                                           (def bar 5)
                                           (ns baz)))
                       combine-responses))
  (is (= [5] (response-values (message session {:op "eval" :code "bar" :ns "user"}))))
  ;; NREPL-72: :ns argument to eval shouldn't affect *ns* outside of the scope of that evaluation
  (is+ {:ns "baz"} (-> (repl-eval session "5") combine-responses)))

(def-repl-test error-on-nonexistent-ns
  (is+ {:status #{:error :namespace-not-found :done}}
       (-> (message timeout-client {:op "eval" :code "(+ 1 1)" :ns (name (gensym))})
           combine-responses
           clean-response)))

(def-repl-test proper-response-ordering
  (is+ [{:value mc/absent, :out (th/newline->sys "100\n")}    ; printed number
        {:value "nil", :out mc/absent}                        ; return val from println
        {:value "42", :out mc/absent}                         ; return val from `42`
        {:value mc/absent, :out mc/absent, :status #{:done}}] ; :done
       (->> (repl-eval client "(println 100) 42")
            (mapv clean-response))))

(def-repl-test interrupt
  (testing "ephemeral session"
    (is+ {:status #{:error :session-ephemeral :done}}
         (clean-response (first (message client {:op "interrupt"}))))
    (is+ {:status #{:error :session-ephemeral :done}}
         (clean-response (first (message client {:op "interrupt" :interrupt-id "foo"})))))

  (testing "registered session"
    (is+ {:status #{:done :session-idle}}
         (clean-response (first (message session {:op "interrupt"}))))
    (is+ {:status #{:done :session-idle}}
         (clean-response (first (message session {:op "interrupt" :interrupt-id "foo"}))))

    (let [resp (message session {:op "eval" :code (code (do
                                                          (def halted? true)
                                                          halted?
                                                          (try (Thread/sleep 30000)
                                                               (def halted? false)
                                                               (catch InterruptedException _))))})]
      (Thread/sleep 100)
      (is+ {:status #{:done :error :interrupt-id-mismatch}}
           (clean-response (first (message session {:op "interrupt" :interrupt-id "foo"}))))
      (is+ {:status #{:done}} (-> session (message {:op "interrupt"}) first clean-response))
      (is+ {:status #{:done :interrupted}} (-> resp combine-responses clean-response))
      (is (= [true] (repl-values session "halted?")))))
  (testing "interrupting a sleep"
    ;; Here and below we use `client-session` to continue collecting responses
    ;; past `:done` until `:session-closed`, because the exception raised by
    ;; interrupt arrives out of order after `:done` response.
    (let [session' (nrepl/client-session client)
          responses (session' {:op "eval"
                               :code (code
                                      (do
                                        (Thread/sleep 10000)
                                        "Done"))})]
      (Thread/sleep 100)
      (session' {:op "interrupt"})
      (Thread/sleep 200) ;; Let interrupt go through and generate an exception
      (session' {:op "close"})
      ;; Can't rely on order, thus `mc/embeds`.
      (is+ (mc/embeds
            [;; response to interrupt msg
             {:status #{:done :interrupted}}
             ;; InterruptedException raised during sleep
             {:ex "class java.lang.InterruptedException"
              :root-ex "class java.lang.InterruptedException",
              :status #{:eval-error}}
             ;; Response to `close`
             {:status #{:done :session-closed}}])
           (mapv clean-response responses))))
  (testing "interruptible code"
    (let [session' (nrepl/client-session client)
          responses (session' {:op "eval"
                               :code (code
                                      (#(if (.isInterrupted (Thread/currentThread))
                                          "Clean stop!"
                                          (recur))))})]
      (Thread/sleep 100)
      (session' {:op "interrupt"})
      (Thread/sleep 200) ;; Let interrupt go through and generate an exception
      (session' {:op "close"})
      (is+ (mc/embeds
            [;; response to interrupt msg
             {:status #{:done :interrupted}}
             ;; Lagging return value produced after interruption
             {:value "\"Clean stop!\""}
             ;; Response to `close`
             {:status #{:done :session-closed}}])
           (mapv clean-response responses)))))

(def-repl-test non-interruptible-stop-thread
  (testing "non-interruptible code can still be interrupted"
    (with-redefs [threading/force-stop-delay-ms 500]
      (let [resp (message session {:op "eval"
                                   :code (code
                                          (do (def vol (volatile! 0))
                                              (def curr-t (Thread/currentThread))
                                              ;; This never stops on its own.
                                              (while (vswap! vol inc))))})]
        (Thread/sleep 1000)
        (is+ {:status #{:done}}
             (-> (message session {:op "interrupt"})
                 first
                 clean-response))
        ;; Wait for CIDER forceful interrupt to trigger.
        (Thread/sleep (+ 500 1000))
        ;; Verify that volatile is not changed anymore.
        (let [v (repl-values session "@vol")]
          (Thread/sleep 1000)
          (is (= v (repl-values session "@vol")))
          (is (= ["TERMINATED"] (repl-values session "(str (.getState curr-t))"))))))))

;; NREPL-66: ensure that bindings of implementation vars aren't captured by user sessions
;; (https://github.com/clojure-emacs/cider/issues/785)
(def-repl-test ensure-no-*msg*-capture
  (let [[r1 r2 :as results] (repeatedly 2 #(repl-eval session "(println :foo)"))
        [ids ids2] (map #(set (map :id %)) results)
        [out1 out2] (map #(-> % combine-responses :out) results)]
    (is (empty? (clojure.set/intersection ids ids2)))
    (is (= (th/newline->sys ":foo\n") out1 out2))))

(def-repl-test read-timeout
  (is (nil? (repl-values timeout-session "(Thread/sleep 1100) :ok")))
  ;; just getting the values off of the wire so the server side doesn't
  ;; toss a spurious stack trace when the client disconnects
  (is (= [nil :ok] (->> (repeatedly #(transport/recv transport 500))
                        (take-while (complement nil?))
                        response-values))))

(def-repl-test concurrent-message-handling
  (testing "multiple messages can be handled on the same connection concurrently"
    (let [sessions (doall (repeatedly 3 #(client-session client)))
          start-time (System/currentTimeMillis)
          elapsed-times (map (fn [session eval-duration]
                               (let [expr (pr-str `(Thread/sleep ~eval-duration))
                                     responses (message session {:op "eval" :code expr})]
                                 (future
                                   (is (= [nil] (response-values responses)))
                                   (- (System/currentTimeMillis) start-time))))
                             sessions
                             [2000 1000 0])]
      (is (apply > (map deref (doall elapsed-times)))))))

(def-repl-test ensure-transport-closeable
  (is (= [5] (repl-values session "5")))
  (is (instance? java.io.Closeable transport))
  (.close transport)
  (is (thrown? java.net.SocketException (repl-values session "5"))))

(def-repl-test ensure-server-closeable
  (server/stop-server *server*)
  (Thread/sleep 100)
  (is (thrown? java.net.ConnectException (connect :port (:port *server*)))))

(defn- disconnection-exception?
  [e]
  ;; thrown? should check for the root cause!
  (let [^Throwable cause (root-cause e)]
    (and (instance? SocketException cause)
         (re-matches #".*(lost.*connection|socket closed).*" (.getMessage cause)))))

(deftest transports-fail-on-disconnects
  (testing "Ensure that transports fail ASAP when the server they're connected to goes down."
    (let [server (server/start-server :transport-fn *transport-fn*)
          transport (connect :port (:port server)
                             :transport-fn *transport-fn*)]
      (transport/send transport {:op "eval" :code "(+ 1 1)"})

      (let [reader (future (while true (transport/recv transport)))]
        (Thread/sleep 100)
        (server/stop-server server)
        (Thread/sleep 100)
        (try
          (deref reader 1000 :timeout)
          (assert false "A reader started prior to the server closing should throw an error...")
          (catch Throwable e
            (is (disconnection-exception? e)))))

      (is (thrown? SocketException (transport/recv transport)))
      ;; The next `Thread/sleep` is needed or the test would be fleaky
      ;; for some transports that don't throw an exception the first time
      ;; a message is sent after the server is closed.
      (try
        (transport/send transport {:op "eval" :code "(+ 5 1)"})
        (catch Throwable t))
      (Thread/sleep 100)
      (is (thrown? SocketException (transport/send transport {:op "eval" :code "(+ 5 1)"}))))))

(deftest server-starts-with-minimal-configuration
  (testing "Ensure server starts with minimal configuration"
    (let [server (server/start-server)
          transport (connect :port (:port server))
          client (client transport Long/MAX_VALUE)]
      (is+ {:value ["3"]}
           (-> (message client {:op "eval" :code "(- 4 1)"})
               combine-responses)))))

(def-repl-test clients-fail-on-disconnects
  (testing "Ensure that clients fail ASAP when the server they're connected to goes down."
    (let [resp (repl-eval client "1 2 3 4 5 6 7 8 9 10")]
      (is+ {:value "1"} (first resp))
      (Thread/sleep 1000)
      (server/stop-server *server*)
      (Thread/sleep 1000)
      (try
        ;; these responses were on the wire before the remote transport was closed
        (is (> 20 (count resp)))
        (transport/recv transport)
        (assert false "reads after the server is closed should fail")
        (catch Throwable t
          (is (disconnection-exception? t)))))

    ;; TODO: as noted in transports-fail-on-disconnects, *sometimes* two sends are needed
    ;; to trigger an exception on send to an unavailable server
    (try (repl-eval session "(+ 1 1)") (catch Throwable _))
    (Thread/sleep 100)
    (is (thrown? SocketException (repl-eval session "(+ 1 1)")))))

(def-repl-test request-*in*
  (is (= '((1 2 3)) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp clean-response :status (contains? :need-input))
                                           (session {:op "stdin" :stdin "(1 2 3)"}))
                                         resp)))))

  (session {:op "stdin" :stdin (th/newline->sys "a\nb\nc\n")})
  (doseq [x "abc"]
    (is (= [(str x)] (repl-values session "(read-line)")))))

(def-repl-test request-*in*-eof
  (is+ nil? (response-values (for [resp (repl-eval session "(read)")]
                               (do
                                 (when (-> resp clean-response :status (contains? :need-input))
                                   (session {:op "stdin" :stdin []}))
                                 resp)))))

(def-repl-test request-multiple-read-newline-*in*
  (is+ [:ohai] (response-values (for [resp (repl-eval session "(read)")]
                                  (do
                                    (when (-> resp clean-response :status (contains? :need-input))
                                      (session {:op "stdin" :stdin (th/newline->sys ":ohai\n")}))
                                    resp))))

  (session {:op "stdin" :stdin (th/newline->sys "a\n")})
  (is+ ["a"] (repl-values session "(read-line)")))

(def-repl-test request-multiple-read-with-buffered-newline-*in*
  (is+ [:ohai] (response-values (for [resp (repl-eval session "(read)")]
                                  (do
                                    (when (-> resp clean-response :status (contains? :need-input))
                                      (session {:op "stdin" :stdin (th/newline->sys ":ohai\na\n")}))
                                    resp))))

  (is+ ["a"] (repl-values session "(read-line)")))

(def-repl-test request-multiple-read-objects-*in*
  (is+ [:ohai] (response-values (for [resp (repl-eval session "(read)")]
                                  (do
                                    (when (-> resp clean-response :status (contains? :need-input))
                                      (session {:op "stdin" :stdin (th/newline->sys ":ohai :kthxbai\n")}))
                                    resp))))

  (is+ [" :kthxbai"] (repl-values session "(read-line)")))

(def-repl-test test-url-connect
  (with-open [conn (url-connect (str (transport-fn->protocol *transport-fn*)
                                     "://127.0.0.1:"
                                     (:port *server*)))]
    (transport/send conn {:op "eval" :code "(+ 1 1)"})
    (is (= [2] (response-values (response-seq conn 100))))))

(deftest test-ack
  (with-server [s (server/start-server :transport-fn *transport-fn*
                                             :handler (-> (server/default-handler)
                                                          ack/handle-ack))]
    (ack/reset-ack-port!)
    (with-server [s2 (server/start-server :transport-fn *transport-fn*
                                                :ack-port (:port s))]
      (is (= (:port s2) (ack/wait-for-ack 10000))))))

(def-repl-test agent-await
  (is+ [42] (repl-values session (code (let [a (agent nil)]
                                         (send a (fn [_] (Thread/sleep 1000) 42))
                                         (await a)
                                         @a)))))

(deftest cloned-session-*1-binding
  (let [port (:port *server*)
        conn (connect :port port :transport-fn *transport-fn*)
        client (nrepl/client conn 1000)
        sess (nrepl/client-session client)
        sess-id (->> (sess {:op "eval"
                            :code "(+ 1 4)"})
                     last
                     :session)
        new-sess-id (->> (sess {:session sess-id
                                :client-name "nREPL test"
                                :client-version "1.2.3"
                                :op "clone"})
                         last
                         :session)
        cloned-sess (nrepl/client-session client :session new-sess-id)
        cloned-sess-*1 (->> (cloned-sess {:session new-sess-id
                                          :op "eval"
                                          :code "*1"})
                            first
                            :value)]
    (is+ "5" cloned-sess-*1)))

(def-repl-test print-namespace-maps-binding
  (when (resolve '*print-namespace-maps*)
    (let [set-true (dorun (repl-eval session "(set! *print-namespace-maps* true)"))
          true-val (first (repl-values session "*print-namespace-maps*"))
          set-false (dorun (repl-eval session "(set! *print-namespace-maps* false)"))
          false-val (first (repl-values session "*print-namespace-maps*"))]
      (is (= true true-val))
      (is (= false false-val)))))

(def-repl-test interrupt-load-file
  (let [resp (message session {:op "load-file"
                               :file (slurp (File. project-base-dir "load-file-test/nrepl/load_file_sample2.clj"))
                               :file-path "nrepl/load_file_sample2.clj"
                               :file-name "load_file_sample2.clj"})]
    (Thread/sleep 100)
    (is+ {:status #{:done}}
         (-> (message session {:op "interrupt"})
             first
             clean-response))
    (Thread/sleep 500)
    (is+ {:status (mc/embeds #{:interrupted})}
         (-> resp
             combine-responses
             clean-response))))

(def-repl-test stdout-stderr
  (is+ {:ns "user" :out (str "5 6 7 \n 8 9 10" th/sys-newline) :value ["nil"]}
       (-> (repl-eval client (code (println 5 6 7 \newline 8 9 10)))
           combine-responses))
  (is+ {:ns "user" :err (th/newline->sys "user/foo\n") :value ["nil"]}
       (-> (repl-eval client (code (binding [*out* *err*] (prn 'user/foo))))
           combine-responses))
  (is+ {:ns "user" :err #"problem" :value [":value"]}
       (-> (repl-eval client (code (do (.write *err* "problem") :value)))
           combine-responses))
  (is+ {:err #"Divide by zero"}
       (-> (repl-eval client (code (/ 1 0)))
           combine-responses)))

(def-repl-test read-error-short-circuits-execution
  (testing "read error prevents the remaining code from being read and executed"
    (is+ {:out mc/absent
          :value mc/absent
          :err (if (and (= (:major *clojure-version*) 1)
                        (<= (:minor *clojure-version*) 9))
                 #"(?s)^RuntimeException Map literal must contain an even number of forms[^\r\n]+[\r]?\n$"
                 ;; BB-TEST-PATCH babashka's reader reports its own message
                 #"Map literals? must contain an even number of forms")}
         (-> (repl-eval client "(comment {:a} (println \"BOOM!\"))")
             combine-responses)))

  (testing "exactly one read error is produced even if there is remaining code in the message"
    (is+ {:out mc/absent
          :value mc/absent
          :err (mc/all-of #"Unmatched delimiter: \)"
                          (mc/mismatch #"Unmatched delimiter: \]")
                          (mc/mismatch #"Unmatched delimiter: \}"))}
         (-> (repl-eval client ")]} 42")
             combine-responses))))

(defn custom-repl-caught
  [^Throwable t]
  (binding [*out* *err*]
    (println "foo" (type t))))

(def-repl-test caught-options
  (testing "bad symbol should fall back to default"
    (is+ [{::middleware.caught/error "Couldn't resolve var my.missing.ns/repl-caught"
           :status #{:nrepl.middleware.caught/error}}
          {:err #"IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (first 1))
                                ::middleware.caught/caught "my.missing.ns/repl-caught"})
              (mapv clean-response))))

  (testing "custom symbol should be used"
    (is+ [{:err (th/newline->sys "foo java.lang.IllegalArgumentException\n")}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (first 1))
                                ::middleware.caught/caught `custom-repl-caught})
              (mapv clean-response))))

  (testing "::print? option"
    (is+ [{:err #"IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"
           ::middleware.caught/throwable #"IllegalArgumentException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (first 1))
                                ::middleware.caught/print? 1})
              (mapv clean-response)))

    (is+ [{:err (th/newline->sys "foo java.lang.IllegalArgumentException\n")}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"
           ::middleware.caught/throwable #"IllegalArgumentException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (first 1))
                                ::middleware.caught/caught `custom-repl-caught
                                ::middleware.caught/print? 1})
              (mapv clean-response)))))

(defn custom-session-repl-caught
  [^Throwable t]
  (binding [*out* *err*]
    (println "bar" (.getMessage t))))

(def-repl-test session-caught-options
  (testing "setting *caught-fn* works"
    (is+ [{:ns "user" :value "#'nrepl.core-test/custom-session-repl-caught"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (set! nrepl.middleware.caught/*caught-fn* (resolve `custom-session-repl-caught)))})
              (mapv clean-response)))

    (is+ [{:err (th/newline->sys "bar Divide by zero\n")}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (/ 1 0))})
              (mapv clean-response))))

  (testing "request can still override *caught-fn*"
    (is+ [{:err (th/newline->sys "foo java.lang.ArithmeticException\n")}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (/ 1 0))
                                ::middleware.caught/caught `custom-repl-caught})
              (mapv clean-response))))

  (testing "request can still provide ::print? option"
    (is+ [{:err #"Divide by zero"}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"
           ::middleware.caught/throwable #"Divide by zero"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (/ 1 0))
                                ::middleware.caught/print? 1})
              (mapv clean-response)))

    (is+ [{:err (th/newline->sys "foo java.lang.ArithmeticException\n")}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"
           ::middleware.caught/throwable #"Divide by zero"}
          {:status #{:done}}]
         (->> (message session {:op "eval"
                                :code (code (/ 1 0))
                                ::middleware.caught/caught `custom-repl-caught
                                ::middleware.caught/print? 1})
              (mapv clean-response)))))

;; This test checks if, within the same session, the ContextClassLoader and
;; RT/baseLoader have a common DynamicClassLoader ancestor. This is what
;; pomegranate (and other classpath modifying libs?) target to enable hotloading

;; This is from pomegranate
(defn classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip]
   (->> tip
        (iterate #(.getParent ^ClassLoader %))
        (take-while boolean))))

(def captured-values-atom (atom {}))

(defn capture-value [key value]
  (swap! captured-values-atom assoc key value)
  nil)

(def-repl-test hotloading-common-classloader-test
  (testing "Check if RT/baseLoader and ContexClassLoader have a common DCL ancestor"
    (repl-values session (code (nrepl.core-test/capture-value
                                "base-loader"
                                (#'nrepl.core-test/classloader-hierarchy (clojure.lang.RT/baseLoader)))))
    (repl-values session (code (nrepl.core-test/capture-value
                                "ccl"
                                (#'nrepl.core-test/classloader-hierarchy (.. Thread currentThread getContextClassLoader)))))
    (let [base-loader-dcls (->> (@captured-values-atom "base-loader")
                                (filter #(instance? clojure.lang.DynamicClassLoader %))
                                set)
          ccl-dcls (->> (@captured-values-atom "ccl")
                        (filter #(instance? clojure.lang.DynamicClassLoader %))
                        set)]
      (is (seq (set/intersection base-loader-dcls ccl-dcls))
          (str "Base loader DCLs: " base-loader-dcls "\nCCL DCLs: " ccl-dcls)))))

(def-repl-test classloader-chain-doesnt-grow-test
  (testing "after doing regular evals, the classloader chain remains of the same length"
    (let [[chain-length] (repl-values session (code (count
                                                     (#'nrepl.core-test/classloader-hierarchy))))]
      ;; Eval some things.
      (dotimes [_ 10] (repl-values session (code (+ 1 2))))
      (is (= chain-length
             (first (repl-values session (code (count
                                                (#'nrepl.core-test/classloader-hierarchy))))))))))

(def-repl-test custom-context-classloader-is-not-overwritten
  (testing "if user eval code has set the custom context classloader, then it persists"
    (repl-values session
                 (code
                  (let [t (Thread/currentThread)
                        new-cl (clojure.lang.DynamicClassLoader.
                                (.getContextClassLoader t))]
                    (.setContextClassLoader t new-cl)
                    (nrepl.core-test/capture-value "new-cl" new-cl))))
    (let [[good?] (repl-values session
                               (code
                                (contains? (set (#'nrepl.core-test/classloader-hierarchy))
                                           (@nrepl.core-test/captured-values-atom "new-cl"))))]
      (is good?))))

(def-repl-test sanity-tests
  (testing "eval"
    (are [expr result] (= result (first (repl-values session (code expr))))
      (+ 1 2) 3
      *1 3
      (set! *print-length* 42) 42
      *print-length* 42))

  (testing "specified-namespace"
    (is+ {:ns "user", :value ["3"], :status #{:done}}
         (->> (message session {:op "eval"
                                :ns "user"
                                :code (code (+ 1 2))})
              (mapv clean-response)
              combine-responses))
    (is+ {:ns "user", :value ["[\"user\" \"++\"]"], :status #{:done}}
         (->> (message session {:op "eval"
                                :ns "user"
                                :code "(do
                                         (def ^{:dynamic true} ++ +)
                                         (mapv #(-> #'++ meta % str) [:ns :name]))"})
              (mapv clean-response)
              combine-responses))
    (is+ {:ns "user", :value ["5"], :status #{:done}}
         (->> (message session {:op "eval"
                                :ns "user"
                                :code (code
                                       (binding [++ -]
                                         (++ 8 3)))})
              (mapv clean-response)
              combine-responses)))

  (testing "multiple-expressions"
    (is+ [4 65536.0] (repl-values session "(+ 1 3) (Math/pow 2 16)"))
    (is+ [4 20 1 0] (repl-values session "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"))
    (is+ [0] (repl-values session "*1"))))
