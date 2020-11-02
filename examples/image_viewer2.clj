#!/usr/bin/env bb

(ns image-viewer
  (:require [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [ring.adapter.jetty :as server])
  (:import [java.net URLDecoder URLEncoder]))

(def cli-options [["-p" "--port PORT" "Port for HTTP server" :default 8090 :parse-fn #(Integer/parseInt %)]
                  ["-d" "--dir DIR" "Directory to scan for images" :default "."]])
(def opts (:options (parse-opts *command-line-args* cli-options)))
(def port (:port opts))
(def dir (:dir opts))

(def images
  (filter #(and (.isFile %)
                (let [nm (.getName %)
                      ext (some-> (str/split nm #"\.")
                                  last
                                  str/lower-case)]
                  (contains? #{"jpg" "jpeg" "png" "gif" "svg"} ext)))
          (file-seq (io/file dir))))

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

(def jetty
  (future
    (server/run-jetty
     (fn [{:keys [:uri]}]
       (cond
         ;; serve the file
         (str/starts-with? uri "/assets")
         (let [f (io/file (-> (str/replace uri "assets" "")
                              (URLDecoder/decode)))]
           {:body f})
         ;; serve html
         (re-matches #"/[0-9]+" uri)
         (let [n (-> (str/replace uri "/" "")
                     (Integer/parseInt))]
           (page n))
         ;; favicon.ico, etc
         :else
         {:status 404}))
     {:port port})))

(browse/browse-url (format "http://localhost:%s/0" port))

@jetty
