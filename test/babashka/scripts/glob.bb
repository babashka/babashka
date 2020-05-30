(require '[clojure.java.io :as io])

(defn glob [pattern]
  (let [matcher (.getPathMatcher
                 (java.nio.file.FileSystems/getDefault)
                 (str "glob:" pattern))]
    (into []
     (comp (filter #(.isFile %))
           (filter #(.matches matcher (.normalize (.toPath %))))
           (map #(.relativize (.toURI (io/file ".")) (.toURI %)))
           (map #(.getPath %)))
     (file-seq (io/file ".")))))

(glob "*/doc/*.md") ;;=> ["sci/doc/libsci.md" "babashka.nrepl/doc/intro.md"]
