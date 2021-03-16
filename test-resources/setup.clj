(defn eval-plus [& args]
  (apply + (map (fn [i]
                  (Integer/parseInt i))
                *command-line-args*)))

(defn tree []
  (babashka.deps/clojure ["-Stree"]))


(defn all [& args]
  (apply eval-plus args)
  (tree))

(defn bash [& args]
  (babashka.process/process (into ["bash"] args)))

