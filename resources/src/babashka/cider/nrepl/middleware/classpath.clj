(ns cider.nrepl.middleware.classpath
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [clojure.java.io :as io]
   [orchard.java.classpath :as cp]
   [orchard.misc :as misc]))

(defn file-url?
  [u]
  (and (misc/url? u)
       (= (.getProtocol ^java.net.URL u) "file")))

(defn classpath-reply [_msg]
  {:classpath (->> (cp/classpath)
                   (filter file-url?)
                   (map io/as-file)
                   (map str))})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/classpath" classpath-reply})))
