(require '[clojure.java.io :as io])

;; file-seq is rooted at test-resources on purpose: walking "." follows any
;; symlink in a dev checkout (e.g. a node_modules link escaping the repo) and
;; can hang forever
(defn glob [pattern]
  (let [matcher (.getPathMatcher
                 (java.nio.file.FileSystems/getDefault)
                 (str "glob:" pattern))]
    (into []
     (comp (filter #(.isFile %))
           (filter #(.matches matcher (.normalize (.toPath %))))
           (map #(.relativize (.toURI (io/file ".")) (.toURI %)))
           (map #(.getPath %)))
     (file-seq (io/file "test-resources")))))

(glob "test-resources/babashka/*.clj") ;;=> ["test-resources/babashka/glob.clj" ...]
