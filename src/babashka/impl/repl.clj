(ns babashka.impl.repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core :as core-extras]
   [babashka.impl.clojure.main :as m]
   [babashka.impl.common :as common]
   [babashka.terminal :as terminal]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.reader-types :as r]
   [sci.core :as sci]
   [sci.impl.interpreter :refer [eval-form]]
   [sci.impl.io :as sio]
   [sci.impl.parser :as parser]
   [sci.impl.utils :as utils])
  (:import
   [org.jline.reader LineReaderBuilder EndOfFileException UserInterruptException]
   [org.jline.terminal TerminalBuilder]))


(set! *warn-on-reflection* true)

(defn repl-caught
  "Default :caught hook for repl"
  [^Throwable e]
  (sci/with-bindings {sci/out @sci/err}
    (let [{:keys [:file :line :column] :as d} (ex-data e)
          sci-error? (identical? :sci/error (:type d))
          ex-name (when sci-error?
                    (some-> ^Throwable (ex-cause e)
                            .getClass .getName))
          ex-message (when-let [m (.getMessage e)]
                       (when-not (str/blank? m)
                         m))]
      (sio/println (str ex-name
                        (when ex-message
                          (str (when ex-name ": ")
                               ex-message))
                        (when file
                          (str
                           (when (or ex-name ex-message)
                             " ")
                           "[at " file
                           (when line
                             (str ":" line ":" column))"]"))))
      (sio/flush))))

(defn skip-if-eol
  "Inspired by skip-if-eol from clojure.main."
  [s]
  (let [c (r/read-char s)]
    (when-not (= \newline c )
      (r/unread s c))))

(defn repl-read [sci-ctx in-stream _request-prompt request-exit]
  (if (nil? (r/peek-char in-stream))
    request-exit
    (let [v (parser/parse-next sci-ctx in-stream)]
      (skip-if-eol in-stream)
      (if (or (identical? :repl/quit v)
            (identical? :repl/exit v))
        request-exit
        v))))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  ([sci-ctx] (repl sci-ctx nil))
  ([sci-ctx {:keys [:init :read :eval :need-prompt :prompt :flush :print :caught]}]
   (let [in @sci/in]
     (sci/binding [core-extras/warn-on-reflection @core-extras/warn-on-reflection
                   core-extras/unchecked-math @core-extras/unchecked-math
                   core-extras/data-readers @core-extras/data-readers
                   sci/ns @sci/ns
                   sci/print-length @sci/print-length]
       (m/repl
        :init (or init
                  (fn []
                    (sci/with-bindings {sci/out @sci/err}
                      (sio/println "Babashka"
                                   (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION" common/jvm-loader))))
                                   "REPL.")
                      (sio/println "Use :repl/quit or :repl/exit to quit the REPL.")
                      (sio/println "Clojure rocks, Bash reaches.")
                      (sio/println))
                    (eval-form sci-ctx `(apply require (quote ~m/repl-requires)))))
        :read (or read
                (fn [request-prompt request-exit]
                     (repl-read sci-ctx in request-prompt request-exit)))
        :eval (or eval
                  (fn [expr]
                    (sci/with-bindings {sci/file "<repl>"
                                        sci/*1 *1
                                        sci/*2 *2
                                        sci/*3 *3
                                        sci/*e *e}
                      (let [ret (eval-form sci-ctx expr)]
                        ret))))
        :need-prompt (or need-prompt (fn [] true))
        :prompt (or prompt #(sio/printf "%s=> " (utils/current-ns-name)))
        :flush (or flush sio/flush)
        :print (or print sio/prn)
        :caught (or caught repl-caught))))))

(defn- create-line-reader
  "Creates a JLine LineReader for interactive input."
  ^org.jline.reader.LineReader []
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))]
    (-> (LineReaderBuilder/builder)
        (.terminal terminal)
        (.build))))

(defn- parse-next-or-nil
  "Parse next form from reader. Returns {:form form :reader reader} on success,
   {:incomplete true} if EOF during parse, {:error e} on error, or nil if reader is exhausted."
  [sci-ctx reader]
  ;; Skip whitespace first
  (loop []
    (let [^java.lang.Character c (r/read-char reader)]
      (cond
        (nil? c) nil  ;; reader exhausted
        (Character/isWhitespace c) (recur)
        :else
        (do
          (r/unread reader c)
          (try
            (let [form (parser/parse-next sci-ctx reader)]
              {:form form :reader reader})
            (catch Exception e
              (let [msg (ex-message e)]
                (if (and msg (or (str/includes? msg "EOF while reading")
                                 (str/includes? msg "EOF while parsing")))
                  {:incomplete true}
                  {:error e})))))))))

(defn- eval-form-print
  "Evaluate a form, print the result, and handle errors. Returns :exit for quit commands, :continue otherwise."
  [sci-ctx form]
  (if (or (identical? :repl/quit form)
          (identical? :repl/exit form))
    :exit
    (do
      (try
        (sci/with-bindings {sci/file "<repl>"
                            sci/*1 *1
                            sci/*2 *2
                            sci/*3 *3
                            sci/*e *e}
          (let [ret (eval-form sci-ctx form)]
            (sio/prn ret)))
        (catch Throwable e
          (repl-caught e)))
      :continue)))

(defn- process-input
  "Process all forms in input string. Returns :exit, :continue, or {:incomplete input} if more input needed."
  [sci-ctx input]
  (let [reader (r/source-logging-push-back-reader input)]
    (loop []
      (let [result (parse-next-or-nil sci-ctx reader)]
        (cond
          ;; Reader exhausted, all forms processed
          (nil? result)
          :continue

          ;; Need more input - return the accumulated input so far
          (:incomplete result)
          {:incomplete input}

          ;; Parse error
          (:error result)
          (do
            (repl-caught (:error result))
            :continue)

          ;; Got a form - evaluate it and continue with remaining input
          :else
          (let [status (eval-form-print sci-ctx (:form result))]
            (if (= :exit status)
              :exit
              (recur))))))))

(defn- jline-repl-iteration
  "Single iteration of the JLine REPL. Returns :exit to quit, :continue otherwise."
  [sci-ctx ^org.jline.reader.LineReader line-reader]
  (let [prompt (str (utils/current-ns-name) "=> ")]
    (try
      (loop [input ""]
        (let [line (.readLine line-reader (if (str/blank? input) prompt "   "))
              input (str input (when-not (str/blank? input) "\n") line)
              result (process-input sci-ctx input)]
          (if (map? result)
            ;; Incomplete - need more input
            (recur (:incomplete result))
            ;; :exit or :continue
            result)))
      (catch EndOfFileException _
        :exit)
      (catch UserInterruptException _
        ;; Ctrl-C clears the current input
        (sio/println)
        :continue))))

(defn- repl-with-jline
  "REPL using JLine for interactive line editing and history."
  [sci-ctx opts]
  (let [line-reader (create-line-reader)]
    (sci/binding [core-extras/warn-on-reflection @core-extras/warn-on-reflection
                  core-extras/unchecked-math @core-extras/unchecked-math
                  core-extras/data-readers @core-extras/data-readers
                  sci/ns @sci/ns
                  sci/print-length @sci/print-length]
      ;; Print banner
      (sci/with-bindings {sci/out @sci/err}
        (sio/println "Babashka"
                     (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION" common/jvm-loader))))
                     "REPL.")
        (sio/println "Use :repl/quit or :repl/exit to quit the REPL.")
        (sio/println "Clojure rocks, Bash reaches.")
        (sio/println))
      ;; Load repl requires
      (eval-form sci-ctx `(apply require (quote ~m/repl-requires)))
      ;; Main REPL loop
      (loop []
        (when (= :continue (jline-repl-iteration sci-ctx line-reader))
          (recur))))))

(defn start-repl!
  ([sci-ctx] (start-repl! sci-ctx nil))
  ([sci-ctx opts]
   (if (terminal/tty? :stdin)
     (repl-with-jline sci-ctx opts)
     (repl sci-ctx opts))))

;;;; Scratch

(comment
  (def in (-> (java.io.StringReader. "(+ 1 2 3)") clojure.lang.LineNumberingPushbackReader.))
  (r/peek-char in)
  (r/read-char in)
  )
