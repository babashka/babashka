#!/usr/bin/env bb

(require '[clojure.java.io :as io])

(defn which [executable]
  (let [path (System/getenv "PATH")
        paths (.split path (System/getProperty "path.separator"))]
    (loop [paths paths]
      (when-first [p paths]
        (let [f (io/file p executable)]
          (if (and (.isFile f)
                   (.canExecute f))
            (.getCanonicalPath f)
            (recur (rest paths))))))))

(when-let [executable (first *command-line-args*)]
  (println (which executable)))
