(ns babashka.impl.clojure.java.browse
  {:no-doc true}
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [sci.core :as sci]))

(def open-url-script (sci/new-dynamic-var '*open-url-script* (atom nil)))

(def os
  (let [os-name (System/getProperty "os.name")
        os-name (str/lower-case os-name)]
    (cond (str/starts-with? os-name "mac os x")
          :mac
          (str/includes? os-name "linux")
          :linux
          (str/includes? os-name "win")
          :windows)))

(defn browse-url [url]
  (let [url (str url)]
    (if-let [script (-> open-url-script deref deref)]
      (sh script url)
      (case os
        :mac (sh "/usr/bin/open" url)
        :linux (sh "xdg-open" url)
        :windows (sh "cmd" "/C" "start" url)))))

(def browse-namespace
  {'*open-url-script* open-url-script
   'browse-url        browse-url})
