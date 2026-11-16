(ns killing-me-softly
  (:refer-clojure :exclude [descendants])
  (:require [babashka.fs :as fs]))

(import 'java.util.List)

(set! *warn-on-reflection* true)

(defn start-spawning [& args]
  (let [depth (or (System/getenv "DEPTH") "0")
        depth (Integer/parseInt depth)]
    (if (= 3 depth) ;; the deepest process
      (do
        (spit (System/getenv "READY_FILE") "")
        (Thread/sleep 100000000))
      (let [ready-file (or (System/getenv "READY_FILE")
                           (str (fs/path (fs/create-temp-dir) "ready")))
            pb (doto (ProcessBuilder. ^List args)
                 (.inheritIO))
            env (.environment pb)
            _ (.put env "DEPTH" (str (inc depth)))
            _ (.put env "READY_FILE" ready-file)
            proc (.start pb)]
        (if (= 0 depth) ;; the top process
          (do
            (loop [n 0]
              (when (and (not (fs/exists? ready-file)) (< n 300))
                (Thread/sleep 100)
                (recur (inc n))))
            (run! (fn [^java.lang.ProcessHandle handle]
                    (prn (.pid handle))
                    (.destroy handle))
                  (let [handle (.toHandle proc)]
                    (cons handle (iterator-seq (.iterator (.descendants handle)))))))
          (Thread/sleep 100000000))))))

(start-spawning "./bb" *file*)
