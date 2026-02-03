(ns babashka.impl.repl
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [babashka.impl.clojure.core :as core-extras]
   [babashka.impl.clojure.main :as m]
   [babashka.impl.common :as common]
   [babashka.nrepl.impl.completions :as completions]
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
   [org.jline.reader LineReaderBuilder EndOfFileException UserInterruptException
    Parser Parser$ParseContext ParsedLine CompletingParsedLine EOFError
    Completer Candidate]
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
                             (str ":" line ":" column)) "]"))))
      (sio/flush))))

(defn skip-if-eol
  "Inspired by skip-if-eol from clojure.main."
  [s]
  (let [c (r/read-char s)]
    (when-not (= \newline c)
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
                                   (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION" common/jvm-loader)))))
                      (sio/print "    Docs: (doc function-name-here)
          (find-doc \"part-of-name-here\")
  Source: (source function-name-here)
 Javadoc: (javadoc java-object-or-class-here)
    Exit: Control+D or :repl/exit or :repl/quit
 Results: Stored in vars *1, *2, *3, an exception in *e")
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

(defn- parsed-line
  "Creates a CompletingParsedLine for JLine."
  ^CompletingParsedLine [^String line cursor]
  (reify CompletingParsedLine
    (word [_] "")
    (wordCursor [_] 0)
    (wordIndex [_] 0)
    (words [_] [])
    (line [_] line)
    (cursor [_] cursor)
    (escape [_ candidate _complete] (str candidate))
    (rawWordCursor [_] 0)
    (rawWordLength [_] 0)))

(defn word-at-cursor
  "Extracts the word being completed at the cursor position.
   Returns [word word-start] or nil if no word found.
   Word characters in Clojure: alphanumeric, -, _, ?, !, *, +, =, <, >, /, ., :, '"
  [^String line ^long cursor]
  (let [word-char? (fn [c]
                     (or (Character/isLetterOrDigit ^char c)
                         (contains? #{\- \_ \? \! \* \+ \= \< \> \/ \. \: \'} c)))]
    (when (pos? cursor)
      (let [start (loop [i (dec cursor)]
                    (if (or (neg? i) (not (word-char? (.charAt line i))))
                      (inc i)
                      (recur (dec i))))
            word (when (< start cursor) (subs line start cursor))]
        (when (and word (pos? (count word)))
          [word start])))))

(defn- completing-parsed-line
  "Creates a CompletingParsedLine with word information for completions."
  ^CompletingParsedLine [^String line ^long cursor word ^long word-start]
  (let [word-cursor (- cursor word-start)]
    (reify CompletingParsedLine
      (word [_] word)
      (wordCursor [_] word-cursor)
      (wordIndex [_] 0)
      (words [_] [word])
      (line [_] line)
      (cursor [_] cursor)
      (escape [_ candidate _complete] (str candidate))
      (rawWordCursor [_] word-cursor)
      (rawWordLength [_] (count word)))))

(defn- clojure-completer
  "Creates a JLine Completer that uses SCI for Clojure completions."
  [sci-ctx]
  (reify Completer
    (complete [_ _ parsed-line candidates]
      (let [word (.word ^ParsedLine parsed-line)]
        (when (and word (pos? (count word)))
          (let [{:keys [completions]} (completions/completions sci-ctx word)]
            (doseq [{:keys [candidate ns type]} completions]
              (.add ^java.util.List candidates
                    (Candidate.
                     candidate          ; value - what gets inserted
                     candidate          ; display - what's shown in menu
                     nil                ; group
                     (when (or ns type) ; descr - description shown
                       (case type
                         "class" "c"
                         ("static-method" "static-field") "s"
                         ("method" "field") "m"
                         "constructor" "ctor"
                         (str (when type type)
                              (when (and type ns) " ")
                              (when ns ns))))
                     nil                ; suffix
                     nil                ; key
                     false)))))))))    ; complete - add space after?

(defn- try-parse-next
  "Try to parse the next form from reader. Returns :ok, :incomplete, or :error."
  [sci-ctx reader]
  (try
    (parser/parse-next sci-ctx reader)
    :ok
    (catch Exception e
      (let [msg (ex-message e)]
        (if (and msg (str/includes? msg "EOF"))
          :incomplete
          :error)))))

(defn complete-form?
  "Returns true if s contains only complete Clojure forms with no trailing incomplete form.
   Returns false if empty/whitespace-only or if there's an incomplete form at the end.
   Returns true for syntax errors (e.g., unmatched delimiter) since the input is 'complete' enough to evaluate."
  [sci-ctx s]
  (if (str/blank? s)
    false
    (let [reader (r/source-logging-push-back-reader s)]
      (loop [found-any false]
        (let [c (r/read-char reader)]
          (cond
            (nil? c) found-any
            (Character/isWhitespace ^Character c) (recur found-any)
            :else
            (do
              (r/unread reader c)
              (case (try-parse-next sci-ctx reader)
                :ok (recur true)
                :incomplete false
                :error true))))))))

(defn- jline-parser
  "Creates a JLine Parser that detects incomplete Clojure forms.
   Throws EOFError for incomplete input, allowing JLine to handle multi-line editing."
  ^Parser [sci-ctx]
  (reify Parser
    (^ParsedLine parse [_this ^String line ^int cursor ^Parser$ParseContext context]
      (if (identical? context Parser$ParseContext/COMPLETE)
        ;; For completions, return parsed line with word information
        (if-let [[word start] (word-at-cursor line cursor)]
          (completing-parsed-line line cursor word start)
          (parsed-line line cursor))
        ;; For accept-line, check if form is complete
        (if (complete-form? sci-ctx line)
          (parsed-line line cursor)
          (throw (EOFError. -1 -1 "Incomplete Clojure form")))))))

(defn- jline-reader
  "Creates a JLine LineReader for interactive input with persistent history."
  ^org.jline.reader.LineReader [sci-ctx]
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))
        history-file (fs/path (fs/home) ".bb_repl_history")]
    (-> (LineReaderBuilder/builder)
        (.terminal terminal)
        (.parser (jline-parser sci-ctx))
        (.completer (clojure-completer sci-ctx))
        (.variable org.jline.reader.LineReader/HISTORY_FILE history-file)
        (.variable org.jline.reader.LineReader/SECONDARY_PROMPT_PATTERN "%P #_=> ")
        (.build))))

(defn- read-remaining
  "Read remaining characters from a reader into a string."
  [reader]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (r/read-char reader)]
        (if (nil? c)
          (str sb)
          (do (.append sb c)
              (recur)))))))

;; Sentinel value for interrupted input - skip eval and print
(def ^:private interrupted (Object.))

(defn- parse-form
  "Parse the next form from input string. Returns [:form form remaining] or nil if empty/whitespace only."
  [sci-ctx input]
  (let [reader (r/source-logging-push-back-reader input)]
    (loop []
      (let [c (r/read-char reader)]
        (cond
          (nil? c) nil
          (Character/isWhitespace ^Character c) (recur)
          :else (do
                  (r/unread reader c)
                  (let [form (parser/parse-next sci-ctx reader)
                        remaining (read-remaining reader)]
                    [:form form remaining])))))))

(defn- jline-read
  "Read function for m/repl that uses JLine for input.
   JLine handles multi-line editing via the Clojure parser.
   Implements Node-style Ctrl+C behavior: first Ctrl+C on empty prompt shows warning,
   second Ctrl+C exits."
  [sci-ctx ^org.jline.reader.LineReader line-reader input-buffer ctrl-c-pending _request-prompt request-exit]
  (try
    (when-let [[_ form remaining]
               (or
                ;; First check if there's buffered input from previous read (multiple forms on one line)
                (when-not (str/blank? @input-buffer)
                  (let [buf @input-buffer]
                    (reset! input-buffer "")
                    (when-let [[_ form remaining] (parse-form sci-ctx buf)]
                      [:form form remaining])))
                ;; Read new input - JLine handles ALL multi-line via our parser
                (let [prompt (str (utils/current-ns-name) "=> ")
                      input (.readLine line-reader prompt)]
                  (when-let [[_ form remaining] (parse-form sci-ctx input)]
                    [:form form remaining])))]
      (reset! input-buffer remaining)
      (reset! ctrl-c-pending false)
      ;; Return form from buffer
      (if (or (identical? :repl/quit form)
              (identical? :repl/exit form))
        request-exit
        form))
    (catch EndOfFileException _
      request-exit)
    (catch UserInterruptException e
      (let [partial-line (.getPartialLine e)]
        (reset! input-buffer "")
        (cond
          ;; Second consecutive Ctrl+C on empty prompt - exit
          (and (= "" partial-line) @ctrl-c-pending)
          request-exit

          ;; First Ctrl+C on empty prompt - show warning
          (= "" partial-line)
          (do
            (reset! ctrl-c-pending true)
            (sio/println "(To exit, press Ctrl+C again or Ctrl+D or type :repl/exit)")
            interrupted)

          ;; Ctrl+C with partial input - clear and continue (resets pending)
          :else
          (do
            (reset! ctrl-c-pending false)
            interrupted))))))

(defn repl-with-line-reader
  "REPL using a JLine LineReader for interactive input.
   Exposed for testing with mock LineReaders."
  [sci-ctx line-reader opts]
  (let [input-buffer (atom "")
        ctrl-c-pending (atom false)]
    (repl sci-ctx
          (merge opts
                 {:need-prompt (constantly false)  ;; JLine handles prompting
                  :prompt (constantly nil)         ;; No-op, JLine handles prompting
                  :read (fn [request-prompt request-exit]
                          (jline-read sci-ctx line-reader input-buffer ctrl-c-pending request-prompt request-exit))
                  :eval (fn [form]
                          (if (identical? form interrupted)
                            interrupted
                            (sci/with-bindings {sci/file "<repl>"
                                                sci/*1 *1
                                                sci/*2 *2
                                                sci/*3 *3
                                                sci/*e *e}
                              (eval-form sci-ctx form))))
                  :print (fn [val]
                           (when-not (identical? val interrupted)
                             (sio/prn val)))}))))

(defn- repl-with-jline
  "REPL using JLine for interactive line editing and history."
  [sci-ctx opts]
  (repl-with-line-reader sci-ctx (jline-reader sci-ctx) opts))

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
  (r/read-char in))
