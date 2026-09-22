;; Times the phases of one classpath resolve, from a project directory:
;; java -cp <clojure-tools jar> clojure.main doc/adr/ai/0002-resolver-phases.clj, or bb doc/adr/ai/0002-resolver-phases.clj
(def start-ms
  (try (.toEpochMilli (.get (.startInstant (.info (java.lang.ProcessHandle/current)))))
       (catch Exception _ nil)))
(def last-t (atom (System/nanoTime)))
(defn phase [label]
  (let [now (System/nanoTime)]
    (println (format "%-28s %6.0f ms" label (/ (- now @last-t) 1e6)))
    (reset! last-t now)))
(println (format "%-28s %6d ms" "process start -> main" (- (System/currentTimeMillis) start-ms)))
(reset! last-t (System/nanoTime))
(require '[clojure.tools.deps :as deps])
(phase "require tools.deps")
(when-let [ms (try (requiring-resolve 'clojure.tools.deps.util.maven/make-system) (catch Exception _ nil))]
  (ms)
  (phase "make-system (Maven DI)"))
(def b1 (deps/create-basis {:project "deps.edn"}))
(phase "create-basis #1")
(def b2 (deps/create-basis {:project "deps.edn"}))
(phase "create-basis #2 (same jvm)")
(println "cp entries" (count (:classpath-roots b1)))
