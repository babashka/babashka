(ns killing-me-softly
  (:refer-clojure :exclude [descendants]))

(import 'java.util.List)

(set! *warn-on-reflection* true)

(defn start-spawning [& args]
  (let [depth (or (System/getenv "DEPTH") "0")
        depth (Integer/parseInt depth)]
    (when-not (= 4 depth) ;; process at depth 4 dies immediately
      (let [pb (doto (ProcessBuilder. ^List args)
                 (.inheritIO))
            _ (.put (.environment pb) "DEPTH"
                    (str (inc depth)))
            proc (.start pb)]
        (if (= 0 depth) ;; the top process
          (do
            (Thread/sleep 500)
            (run! (fn [^java.lang.ProcessHandle handle]
                    (prn (.pid handle))
                    (.destroy handle))
                  (let [handle (.toHandle proc)]
                    (cons handle (iterator-seq (.iterator (.descendants handle)))))))
          (Thread/sleep 100000000))))))

(start-spawning "./bb" *file*)
