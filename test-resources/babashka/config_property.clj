(ns babashka.config-property
  (:require [babashka.fs :as fs]))

(def prop (System/getProperty "babashka.config"))

(prn (boolean (seq prop)))

(when prop
    (prn (fs/exists? (System/getProperty "babashka.config"))))
