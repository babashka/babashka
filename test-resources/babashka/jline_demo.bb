#!/usr/bin/env bb

;; JLine demo script — tests that JLine features work from user space.
;; Run with: bb test-resources/babashka/jline_demo.bb

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.reader LineReader LineReader$SuggestionType LineReaderBuilder
          EndOfFileException UserInterruptException
          Completer Candidate Widget Reference
          Parser Parser$ParseContext ParsedLine EOFError]
        '[org.jline.reader.impl LineReaderImpl]
        '[org.jline.keymap KeyMap]
        '[org.jline.utils AttributedString AttributedStringBuilder AttributedStyle])

(require '[clojure.string :as str]
         '[babashka.fs :as fs])

;; Simple word completer for a few commands
(def commands ["help" "greet" "quit" "time" "env" "version"])

(defn make-completer []
  (reify Completer
    (complete [_ _reader parsed-line candidates]
      (let [word (.word ^ParsedLine parsed-line)
            pat (when (and word (pos? (count word)))
                  (re-pattern (str "^" (java.util.regex.Pattern/quote word))))]
        (doseq [cmd commands
                :when (or (nil? pat) (re-find pat cmd))]
          (.add ^java.util.List candidates
                (Candidate. cmd cmd nil nil nil nil false)))))))

;; Simple parser that accepts single-line input
(defn make-parser []
  (reify Parser
    (^ParsedLine parse [_ ^String line ^int cursor ^Parser$ParseContext _ctx]
      (let [word-start (loop [i (dec cursor)]
                         (if (or (neg? i)
                                 (Character/isWhitespace (.charAt line i)))
                           (inc i)
                           (recur (dec i))))
            word (subs line word-start cursor)]
        (reify ParsedLine
          (word [_] word)
          (wordCursor [_] (- cursor word-start))
          (wordIndex [_] 0)
          (words [_] [word])
          (line [_] line)
          (cursor [_] cursor))))))

;; Styled prompt
(def prompt-style (-> AttributedStyle/DEFAULT (.bold) (.foreground AttributedStyle/CYAN)))

(defn styled-prompt []
  (let [asb (AttributedStringBuilder.)]
    (.styled asb prompt-style "demo> ")
    (str (.toAnsi (.toAttributedString asb)))))

;; Custom widget: show the current time below prompt
(defn time-widget [^LineReaderImpl line-reader]
  (let [post-field (doto (.getDeclaredField LineReaderImpl "post")
                     (.setAccessible true))]
    (reify Widget
      (apply [_]
        (let [time-str (str (java.time.LocalTime/now))
              asb (AttributedStringBuilder.)]
          (.styled asb (.foreground AttributedStyle/DEFAULT AttributedStyle/YELLOW)
                   (str "Current time: " time-str))
          (.set post-field line-reader
                (reify java.util.function.Supplier
                  (get [_] (.toAttributedString asb))))
          (.redisplay line-reader)
          (.readBinding line-reader (.getKeys line-reader) nil)
          (.set post-field line-reader nil)
          (when-let [s (.getLastBinding line-reader)]
            (.runMacro line-reader s)))
        true))))

;; Handle commands
(defn handle-input [input]
  (let [trimmed (str/trim input)]
    (case trimmed
      "help" (println "Commands: help, greet, quit, time, env, version")
      "greet" (println "Hello from JLine in babashka!")
      "time" (println "Current time:" (str (java.time.LocalTime/now)))
      "env" (println "SHELL:" (System/getenv "SHELL"))
      "version" (println "Babashka" (System/getProperty "babashka.version"))
      "quit" :quit
      (when-not (str/blank? trimmed)
        (println (str "Unknown command: " trimmed ". Type 'help' for help."))))))

;; Main
(let [terminal (-> (TerminalBuilder/builder)
                   (.system true)
                   (.build))
      reader (-> (LineReaderBuilder/builder)
                 (.terminal terminal)
                 (.parser (make-parser))
                 (.completer (make-completer))
                 (.variable LineReader/HISTORY_FILE
                            (fs/path (fs/home) ".bb_jline_demo_history"))
                 (.build))]
  ;; Enable ghost-text autosuggestion
  (.setAutosuggestion reader LineReader$SuggestionType/HISTORY)
  ;; Register time widget on Ctrl+T
  (.put (.getWidgets reader) "show-time" (time-widget reader))
  (let [^KeyMap km (.get (.getKeyMaps reader) LineReader/EMACS)]
    (.bind km (Reference. "show-time") (str (KeyMap/ctrl \T))))
  (println "JLine demo — tab-complete commands, Ctrl+T for time, Ctrl+D to exit")
  (println "Commands:" (str/join ", " commands))
  (loop []
    (let [input (try (.readLine reader (styled-prompt))
                     (catch EndOfFileException _ :eof)
                     (catch UserInterruptException _ :interrupted))]
      (cond
        (= :eof input) (println "\nBye!")
        (= :interrupted input) (do (println) (recur))
        (= :quit (handle-input input)) (println "Bye!")
        :else (recur)))))
