#!/usr/bin/env bb

(defn stash [file-name]
  (with-open [os (java.util.zip.GZIPOutputStream. (io/output-stream (str "/tmp/" file-name ".zip")))]
    (.write os (.getBytes (slurp *in*)))
    (.finish os))
  'ok)

(defn unstash [file-name]
  (print (slurp (java.util.zip.GZIPInputStream. (io/input-stream (str "/tmp/" file-name ".zip"))))))

(let [[action stash-name] *command-line-args*]
  (case action
    "put" (stash stash-name)
    "get" (unstash stash-name)
    (println "Invalid op:" action)))
