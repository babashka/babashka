#!/usr/bin/env bb

;; Benchmark to compare FFM vs Exec provider performance in JLine
;; Run with: bb jline-bench.clj

(ns jline-bench
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.terminal.spi TerminalProvider TerminalExt SystemStream]
           [org.jline.utils AttributedString AttributedStringBuilder AttributedStyle Display]))

(def results (atom {}))

(defn bench [provider-name label n f]
  (let [start (System/nanoTime)
        _ (dotimes [_ n] (f))
        elapsed (/ (- (System/nanoTime) start) 1e6)
        per-op (/ elapsed n)]
    (swap! results assoc-in [label provider-name] {:total elapsed :per-op per-op :n n})
    per-op))

(defn test-provider [provider-name]
  ;; First check if provider is available
  (let [provider (try
                   (TerminalProvider/load provider-name)
                   (catch Exception e
                     nil))]
    (when provider
      ;; Test isSystemStream (TTY detection)
      (bench provider-name "TTY detection" 100
             #(.isSystemStream provider SystemStream/Output))

      ;; Create terminal for further tests - force same provider
      (let [terminal (-> (TerminalBuilder/builder)
                         (.provider provider-name)
                         (.system true)
                         (.build))
            ^TerminalExt terminal-ext terminal
            actual-provider (.name (.getProvider terminal-ext))]
        (swap! results assoc-in ["_provider" provider-name] actual-provider)
        (try
          (bench provider-name "getSize" 1000
                 #(let [size (.getSize terminal)]
                    (.getColumns size)
                    (.getRows size)))

          (bench provider-name "getAttributes" 100
                 #(.getAttributes terminal))

          (bench provider-name "enterRawMode" 50
                 #(let [saved (.enterRawMode terminal)]
                    (.setAttributes terminal saved)))

          (let [writer (.writer terminal)]
            (bench provider-name "write+flush" 500
                   #(do (.write writer "████████████████████████████████████████████████████████████████████████████████\n")
                        (.flush writer))))

          (let [display (Display. terminal false)
                width 40
                height 20
                style-green (-> AttributedStyle/DEFAULT (.foreground AttributedStyle/GREEN))
                build-frame (fn []
                              (let [lines (java.util.ArrayList.)]
                                (.add lines (AttributedString. (str "+" (apply str (repeat (* width 2) "-")) "+")))
                                (doseq [y (range height)]
                                  (let [asb (AttributedStringBuilder.)]
                                    (.append asb "|")
                                    (doseq [x (range width)]
                                      (if (and (= x 10) (= y 10))
                                        (do (.style asb style-green) (.append asb "██") (.style asb AttributedStyle/DEFAULT))
                                        (.append asb "  ")))
                                    (.append asb "|")
                                    (.add lines (.toAttributedString asb))))
                                (.add lines (AttributedString. (str "+" (apply str (repeat (* width 2) "-")) "+")))
                                lines))]
            (.resize display (+ height 2) (+ (* width 2) 2))
            (bench provider-name "Display.update (full)" 200
                   #(.update display (build-frame) 0)))

          (let [display (Display. terminal false)
                width 40
                height 20
                lines (let [lines (java.util.ArrayList.)]
                        (.add lines (AttributedString. (str "+" (apply str (repeat (* width 2) "-")) "+")))
                        (doseq [_ (range height)]
                          (.add lines (AttributedString. (str "|" (apply str (repeat (* width 2) " ")) "|"))))
                        (.add lines (AttributedString. (str "+" (apply str (repeat (* width 2) "-")) "+")))
                        lines)]
            (.resize display (+ height 2) (+ (* width 2) 2))
            (.update display lines 0)
            (bench provider-name "Display.update (diff)" 500
                   #(.update display lines 0)))

          (finally
            (.close terminal)))))))

(defn print-results []
  (println "\nJLine Provider Performance Benchmark")
  (println "=====================================")
  (println (str "Platform: " (System/getProperty "os.name")))
  (println (str "Java: " (System/getProperty "java.version")))
  (println)
  (let [exec-actual (get-in @results ["_provider" "exec"])
        ffm-actual (get-in @results ["_provider" "ffm"])]
    (println (format "Exec provider: %s" (or exec-actual "N/A")))
    (println (format "FFM provider:  %s" (or ffm-actual "N/A"))))
  (println)
  (println (format "%-25s %12s %12s %12s" "Operation" "Exec (ms)" "FFM (ms)" "Speedup"))
  (println (apply str (repeat 63 "-")))

  (doseq [label ["TTY detection" "getSize" "getAttributes" "enterRawMode"
                 "write+flush" "Display.update (full)" "Display.update (diff)"]]
    (let [exec-data (get-in @results [label "exec"])
          ffm-data (get-in @results [label "ffm"])]
      (when (and exec-data ffm-data)
        (let [exec-ms (:per-op exec-data)
              ffm-ms (:per-op ffm-data)
              speedup (/ exec-ms ffm-ms)]
          (println (format "%-25s %12.4f %12.4f %11.1fx" label exec-ms ffm-ms speedup))))))

  (println))

(defn -main [& _args]
  ;; Test both providers
  (test-provider "exec")
  (test-provider "ffm")

  ;; Clear screen and print results
  (print "\u001b[2J\u001b[H")
  (flush)
  (print-results))

(-main)
