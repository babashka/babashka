(require '[clojure.java.io :as io])

;; globs a bounded fixture dir (not the whole repo) so the test stays fast
;; regardless of the working-tree size
(def root "test-resources/babashka/glob")

(defn glob [pattern]
  (let [matcher (.getPathMatcher
                 (java.nio.file.FileSystems/getDefault)
                 (str "glob:" pattern))]
    (into []
     (comp (filter #(.isFile %))
           (filter #(.matches matcher (.normalize (.toPath %))))
           (map #(.relativize (.toURI (io/file ".")) (.toURI %)))
           (map #(.getPath %)))
     (file-seq (io/file root)))))

(glob "**/doc/*.md") ;;=> ["test-resources/babashka/glob/somelib/doc/intro.md"]
