(ns orchard.util.os
  "Operating system specific utilities."
  {:author "Masashi Iizuka"
   :added "0.5"}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def os-type
  (condp #(str/includes? %2 %1) (str/lower-case (System/getProperty "os.name"))
    "linux" ::linux
    "mac" ::mac
    "windows" ::windows
    "bsd" ::bsd
    ::not-supported))

(def user-home-dir (System/getProperty "user.home"))

(defn cache-dir
  "Returns user's cache directory as a File object.

  macOS  : $HOME/Library/Caches
  Windows: %LOCALAPPDATA%\\<AppName>\\Cache
  Others : $XDG_CACHE_HOME or $HOME/.cache"
  {:added "0.5"}
  []
  (case os-type
    ::mac
    (io/file user-home-dir "Library" "Caches")

    ::windows
    (let [appdata (System/getenv "LOCALAPPDATA")]
      (if (str/blank? appdata)
        (io/file user-home-dir "AppData" "Local" "Cache")
        (io/file appdata "Cache")))

    (let [cache-home (System/getenv "XDG_CACHE_HOME")]
      (if (str/blank? cache-home)
        (io/file user-home-dir ".cache")
        (io/file cache-home)))))
