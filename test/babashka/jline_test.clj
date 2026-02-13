(ns babashka.jline-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(defn bb
  [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply tu/bb nil (map str args))))

(deftest jline-classes-available-test
  (testing "JLine terminal classes are available"
    (is (true? (bb '(class? org.jline.terminal.Terminal))))
    (is (true? (bb '(class? org.jline.terminal.TerminalBuilder))))
    (is (true? (bb '(class? org.jline.terminal.Size))))
    (is (true? (bb '(class? org.jline.reader.LineReaderBuilder))))
    (is (true? (bb '(class? org.jline.utils.AttributedString))))))

(deftest jline-terminal-builder-test
  (testing "TerminalBuilder can create a dumb terminal"
    ;; In CI there's no TTY, so we explicitly create a dumb terminal
    ;; This tests that the SPI discovery works (TerminalBuilder finds providers)
    (is (true? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                       (.dumb true)
                                       (.build))]
                      (try
                        (instance? org.jline.terminal.Terminal terminal)
                        (finally
                          (.close terminal)))))))))

(deftest jline-terminal-close-test
  (testing "Terminal can be created and closed without error"
    ;; This primarily tests that SPI discovery works and terminal can be built
    (is (nil? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                      (.dumb true)
                                      (.build))]
                     (.close terminal)))))))

(deftest jline-size-test
  (testing "Terminal Size class works"
    (is (= [80 24] (bb '(let [size (org.jline.terminal.Size. 80 24)]
                          [(.getColumns size) (.getRows size)]))))))

(deftest jline-infocmp-test
  (testing "InfoCmp capabilities are accessible"
    ;; valueOf returns an enum, so we convert to string for EDN compatibility
    (is (= "clear_screen"
           (bb '(str (org.jline.utils.InfoCmp$Capability/valueOf "clear_screen")))))))

(deftest jline-terminal-methods-test
  (testing "Terminal methods work (Terminal detected before Closeable)"
    ;; Terminal extends Closeable, so it must be detected before Closeable
    ;; to access Terminal-specific methods like .reader, .writer, .getName, .getType
    (is (true? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                       (.dumb true)
                                       (.build))]
                      (try
                        (and (some? (.reader terminal))
                             (some? (.writer terminal))
                             (string? (.getName terminal))
                             (string? (.getType terminal)))
                        (finally
                          (.close terminal)))))))))

(deftest jline-nonblockingreader-test
  (testing "NonBlockingReader methods work (detected before Closeable)"
    ;; NonBlockingReader extends Reader which extends Closeable
    ;; Must be detected before Closeable to access .read(timeout) method
    (is (neg? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                      (.dumb true)
                                      (.build))
                         reader (.reader terminal)]
                     (try
                       ;; .read with timeout returns -2 when no input available
                       ;; MB: or -1 when EOF (in case there is no input device?)
                       (.read reader 1)
                       (finally
                         (.close terminal)))))))))

(deftest jline-attributed-string-test
  (testing "AttributedString can be constructed and used"
    (is (= "hello" (bb '(str (org.jline.utils.AttributedString. "hello")))))
    (is (= 5 (bb '(.length (org.jline.utils.AttributedString. "hello")))))
    (is (= 5 (bb '(.columnLength (org.jline.utils.AttributedString. "hello")))))))

(deftest jline-attributed-string-builder-test
  (testing "AttributedStringBuilder can build styled strings"
    (is (= "hello world"
           (bb '(let [asb (org.jline.utils.AttributedStringBuilder.)]
                  (.append asb "hello ")
                  (.style asb (-> org.jline.utils.AttributedStyle/DEFAULT (.bold)))
                  (.append asb "world")
                  (str (.toAttributedString asb))))))
    (is (= 11
           (bb '(let [asb (org.jline.utils.AttributedStringBuilder.)]
                  (.append asb "hello world")
                  (.length (.toAttributedString asb))))))))

(deftest jline-linereader-test
  (testing "LineReader classes are available"
    (is (true? (bb '(class? org.jline.reader.LineReader))))
    (is (true? (bb '(class? org.jline.reader.EndOfFileException))))
    (is (true? (bb '(class? org.jline.reader.UserInterruptException)))))
  (testing "LineReader can read a line"
    (is (= "hello world"
           (bb '(let [input (java.io.ByteArrayInputStream. (.getBytes "hello world\n"))
                      output (java.io.ByteArrayOutputStream.)
                      terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                   (.dumb true)
                                   (.streams input output)
                                   (.build))
                      reader (-> (org.jline.reader.LineReaderBuilder/builder)
                                 (.terminal terminal)
                                 (.build))]
                  (try
                    (.readLine reader)
                    (finally
                      (.close terminal)))))))))

(deftest jline-display-test
  (testing "Display can be constructed and used"
    (is (true? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                       (.dumb true)
                                       (.build))
                          display (org.jline.utils.Display. terminal false)]
                      (try
                        (.resize display 24 80)
                        (.clear display)
                        (instance? org.jline.utils.Display display)
                        (finally
                          (.close terminal)))))))))

(deftest jline-keymap-test
  (testing "KeyMap can be constructed and used"
    (is (true? (bb '(let [km (org.jline.keymap.KeyMap.)]
                      (.bind km :action "a")
                      (= :action (.getBound km "a"))))))))

(deftest jline-attributes-test
  (testing "Attributes can be constructed and used"
    (is (true? (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                       (.dumb true)
                                       (.build))
                          attrs (.getAttributes terminal)]
                      (try
                        (instance? org.jline.terminal.Attributes attrs)
                        (finally
                          (.close terminal)))))))))

(deftest jline-candidate-test
  (testing "Candidate can be constructed and its methods work"
    (is (= ["foo" "foo" nil nil nil nil true]
           (bb '(let [c (org.jline.reader.Candidate. "foo")]
                  [(.value c) (.displ c) (.group c) (.descr c)
                   (.suffix c) (.key c) (.complete c)]))))))

(deftest jline-reify-parser-test
  (testing "Parser can be reified and parse input"
    (is (= ["world" 5 "hello world" 11]
           (bb '(let [parser (reify org.jline.reader.Parser
                               (^org.jline.reader.ParsedLine parse
                                [_ ^String line ^int cursor ^org.jline.reader.Parser$ParseContext _ctx]
                                (let [word-start (loop [i (dec cursor)]
                                                   (if (or (neg? i)
                                                           (Character/isWhitespace (.charAt line i)))
                                                     (inc i)
                                                     (recur (dec i))))
                                      word (subs line word-start cursor)]
                                  (reify org.jline.reader.ParsedLine
                                    (word [_] word)
                                    (wordCursor [_] (- cursor word-start))
                                    (wordIndex [_] 0)
                                    (words [_] [word])
                                    (line [_] line)
                                    (cursor [_] cursor)))))
                      pl (.parse parser "hello world" 11 nil)]
                  [(.word pl) (.wordCursor pl) (.line pl) (.cursor pl)]))))))

(deftest jline-reify-completer-test
  (testing "Completer can be reified and produce completions"
    (is (= ["help" "hello"]
           (bb '(let [completer (reify org.jline.reader.Completer
                                  (complete [_ _reader parsed-line candidates]
                                    (let [word (.word ^org.jline.reader.ParsedLine parsed-line)]
                                      (doseq [cmd ["help" "hello" "quit"]
                                              :when (.startsWith ^String cmd ^String word)]
                                        (.add ^java.util.List candidates
                                              (org.jline.reader.Candidate. cmd))))))
                      ;; create a minimal ParsedLine for testing
                      pl (reify org.jline.reader.ParsedLine
                           (word [_] "hel")
                           (wordCursor [_] 3)
                           (wordIndex [_] 0)
                           (words [_] ["hel"])
                           (line [_] "hel")
                           (cursor [_] 3))
                      candidates (java.util.ArrayList.)]
                  (.complete completer nil pl candidates)
                  (mapv #(.value ^org.jline.reader.Candidate %) candidates)))))))

(deftest jline-reify-widget-test
  (testing "Widget can be reified"
    (is (true? (bb '(let [w (reify org.jline.reader.Widget
                            (apply [_] true))]
                      (.apply w)))))))
