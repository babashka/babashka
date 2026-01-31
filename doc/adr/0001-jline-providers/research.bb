#!/usr/bin/env bb

;; Research script for TTY detection on different platforms
;; Uses only public .spi classes (no .impl)

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.terminal.spi TerminalProvider SystemStream TerminalExt])

(println "=== TTY Detection Research ===\n")

(println "--- Available Providers ---")
(let [providers (.getProviders (TerminalBuilder/builder) nil (IllegalStateException.))]
  (println "Providers found:" (mapv #(.name %) providers))
  (println "First (preferred):" (some-> (first providers) .name)))

(println "\n--- Testing TerminalProvider/load ---")
(doseq [provider-name ["ffm" "exec"]]
  (println (str "Loading '" provider-name "':"))
  (try
    (let [p (TerminalProvider/load provider-name)]
      (println "  Loaded:" (.name p))
      (println "  isSystemStream stdin:" (.isSystemStream p SystemStream/Input))
      (println "  isSystemStream stdout:" (.isSystemStream p SystemStream/Output))
      (println "  isSystemStream stderr:" (.isSystemStream p SystemStream/Error)))
    (catch Throwable e
      (println "  FAILED:" (class e) (ex-message e)))))

(println "\n--- Testing TerminalBuilder ---")
(try
  (let [t (-> (TerminalBuilder/builder)
              (.system true)
              (.build))
        ^TerminalExt te t]
    (println "Terminal class:" (.getClass t))
    (println "Terminal type:" (.getType t))
    (println "Terminal name:" (.getName t))
    (println "Provider:" (.name (.getProvider te)))
    (.close t))
  (catch Throwable e
    (println "TerminalBuilder FAILED:" (class e) (ex-message e))))

(println "\n--- Testing babashka.terminal/tty? ---")
(require '[babashka.terminal :as terminal])
(println "tty? :stdin ->" (terminal/tty? :stdin))
(println "tty? :stdout ->" (terminal/tty? :stdout))
(println "tty? :stderr ->" (terminal/tty? :stderr))

(println "\n=== Done ===")
