(ns nrepl.test-helpers
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [nrepl.core :as nrepl]
            [matcher-combinators.test])
  ;; BB-TEST-PATCH com.hypirion.io is not in the image
  (:import #_(com.hypirion.io ClosingPipe Pipe)
           (java.io File)
           (java.net ServerSocket)))

(def sys-newline
  "The system's newline character sequence."
  (System/lineSeparator))

(def win?
  "Whether we are running on MS-Windows."
  (.startsWith (System/getProperty "os.name") "Windows"))

(defn string=
  "Like `clojure.core/=` applied on STRING1 and STRING2, but treats
  all line endings as equal."
  [string1 string2]
  (= (str/split-lines string1) (str/split-lines string2)))

(defn newline->sys
  "Returns TEXT, converting `\n` line endings to those of the underlying
  system's, if different."
  [text]
  (if-not (= sys-newline "\n")
    (str/replace text "\n" sys-newline)
    text))

(def sys-file-sep
  "The character separating components in a path."
  File/separator)

(defn dir-sep->sys
  "Replace the `/` separator component used in tests with the one used
  by the system."
  [path]
  (if-not (= sys-file-sep "/")
    (str/replace path "/" sys-file-sep)
    path))

;; BB-TEST-PATCH only the cmdline tests use sh
#_(defn sh
  "A version of clojure.java.shell/sh that streams in/out/err.
  Taken and edited from https://github.com/technomancy/leiningen/blob/f7e1adad6ff5137d6ea56bc429c3b620c6f84128/leiningen-core/src/leiningen/core/eval.clj"
  ^Process
  [cmd]
  (let [proc (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String cmd))]
    (future (with-open [out (.getInputStream proc)
                        err (.getErrorStream proc)
                        in (.getOutputStream proc)]
              (let [_pump-out (doto (Pipe. out System/out) .start)
                    _pump-err (doto (Pipe. err System/err) .start)
                    _pump-in (ClosingPipe. System/in in)]
                (.waitFor proc))))
    proc))

(defmacro with-process [[binding cmd-vector] & body]
  (let [sym (gensym)]
    `(let [~sym (sh ~cmd-vector)
           ~binding ~sym]
       (try ~@body
            (finally (.destroy ~binding))))))

(defmacro with-timeout
  "Runs BODY on another thread and gives up after MS milliseconds, throwing
  instead of hanging the whole test run. For code that blocks on responses
  with no timeout of its own, like the built-in client."
  [ms & body]
  `(let [f# (future ~@body)
         v# (deref f# ~ms ::timeout)]
     (if (identical? v# ::timeout)
       (do (future-cancel f#)
           (throw (ex-info (str "Timed out after " ~ms "ms") {:timeout-ms ~ms})))
       v#)))

(defn free-port []
  (let [sock (ServerSocket. 0)
        port (.getLocalPort sock)]
    (.close sock)
    port))

;; nrepl-specific helpers

(defn eval-value1
  "Transform `code-form` into a string, send to `client-or-session` via `eval` op,
  read and combine the response and return the first successful value."
  [client-or-session code-form]
  (-> (nrepl/message client-or-session {:op "eval", :code (nrepl/code* code-form)})
      nrepl/response-values
      first))

;; matcher-combinators.test is needed for `match?`

(defmacro is+
  "Like `is` but wraps expected value in matcher-combinators's `match?`."
  ([expected actual]
   `(is+ ~expected ~actual nil))
  ([expected actual message]
   `(is (~'match? ~expected ~actual) ~message)))
