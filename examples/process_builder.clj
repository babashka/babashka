#!/usr/bin/env bb

(require '[clojure.java.io :as io])
(import '[java.lang ProcessBuilder$Redirect])

(defn grep [input pattern]
  (let [proc (-> (ProcessBuilder. ["grep" pattern])
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT)
                 (.start))
        proc-input (.getOutputStream proc)]
    (with-open [w (io/writer proc-input)]
      (binding [*out* w]
        (print input)
        (flush)))
    (.waitFor proc)
    nil))

(grep "hello\nbye\n" "bye")
