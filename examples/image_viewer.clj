#!/usr/bin/env bb

(ns image-viewer
  (:require [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server])
  (:import [java.net URLDecoder URLEncoder]))

(def images
  (filter #(and (.isFile %)
                (let [name (.getName %)
                      ext (some-> (str/split name #"\.")
                                  last
                                  str/lower-case)]
                  (contains? #{"jpg" "jpeg" "png" "gif" "svg"} ext)))
          (file-seq (io/file "."))))

(def image-count (count images))

(defn page [n]
  (let [prev (max 0 (dec n))
        next (min (inc n) (dec image-count))
        file-path (.getCanonicalPath (nth images n))
        encoded-file-path (URLEncoder/encode file-path)]
    {:body (format "
<!DOCTYPE html>
<html>
<head>
<meta charset=\"utf-8\"/>
<script>
window.onkeydown=function(e) {
  switch (e.key) {
    case \"ArrowLeft\":
      window.location.href=\"/%s\"; break;
    case \"ArrowRight\":
      window.location.href=\"/%s\"; break;
  }
}
</script>
</head>
<body>
Navigation: use left/right arrow keys
<p>%s</p>
<div>
<img style=\"max-height: 90vh; margin: auto; display: block;\" src=\"assets/%s\"/>
</div>
<div>
</div>
</body>

</html>" prev next file-path encoded-file-path)}))

(server/run-server
 (fn [{:keys [:uri]}]
   (if (str/starts-with? uri "/assets")
     ;; serve the file
     (let [f (io/file (-> (str/replace uri "assets" "")
                          (URLDecoder/decode)))]
       {:body f})
     ;; serve html
     (let [n (-> (str/replace uri "/" "")
                 (Integer/parseInt))]
       (page n)))))

(browse/browse-url "http://localhost:8090/0")

@(promise)
