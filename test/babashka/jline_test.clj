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
    (is (true? (bb '(class? org.jline.reader.LineReaderBuilder))))))

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
    (is (= -2 (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                      (.dumb true)
                                      (.build))
                         reader (.reader terminal)]
                     (try
                       ;; .read with timeout returns -2 when no input available
                       (.read reader 1)
                       (finally
                         (.close terminal)))))))))

(deftest jline-ffm-provider-test
  (testing "FFM provider is available"
    ;; Check that FFM provider class is available
    (is (true? (bb '(class? org.jline.terminal.impl.ffm.FfmTerminalProvider)))
        "FfmTerminalProvider class should be available"))
  (testing "FFM provider can create terminal when TTY is available"
    ;; This test creates a real FFM terminal when TTY is present
    ;; When no TTY, FFM provider will still work but create a dumb terminal
    (when (bb '(some? (System/console)))
      (let [res (bb '(let [terminal (-> (org.jline.terminal.TerminalBuilder/builder)
                                        (.ffm true)
                                        (.system true)
                                        (.build))]
                       (try
                         (instance? org.jline.terminal.Terminal terminal)
                         (finally
                           (.close terminal)))))]
        (println "Testing FFM:" res)
        ;; When TTY is available, verify we can create a terminal with FFM provider
        ;; Successfully building without exception proves FFM works
        (is (true? res)
            "FFM provider should create a valid terminal")))))
