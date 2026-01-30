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
