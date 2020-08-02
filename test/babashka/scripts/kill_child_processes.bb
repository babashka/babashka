(import 'java.util.List)

(set! *warn-on-reflection* true)

(defn child-process-handles [^java.lang.ProcessHandle x]
  (distinct
   (cons x
         (mapcat child-process-handles
                 (iterator-seq (.iterator (.descendants x)))))))

(defn start-spawning [& args]
  (let [depth (or (System/getenv "DEPTH") "0")
        depth (Integer/parseInt depth)]
    (when-not (= 4 depth)
      (let [pb (doto (ProcessBuilder. ^List args)
                 (.inheritIO))
            _ (.put (.environment pb) "DEPTH"
                    (str (inc depth)))
            proc (.start pb)]
        (if (= 0 depth) ;; the top process
          (do
            (Thread/sleep 500)
            (run! (fn [^java.lang.ProcessHandle handle]
                    (do (prn (.pid handle))
                        (.destroy handle)))
                  (child-process-handles (.toHandle proc))))
          (Thread/sleep 100000000))))))

(start-spawning "./bb" *file*)
