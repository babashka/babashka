;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.gitlibs.config
  "Implementation, use at your own risk"
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- read-config-value
  "Read a config value from each of these in order, taking the first value found:
   * Java system property
   * env variable
   * default value"
  [property env default]
  (or
    (System/getProperty property)
    (System/getenv env)
    default))

(defn- init-config
  []
  {:gitlibs/dir
   (.getCanonicalPath
     (let [lib-dir (or (System/getProperty "clojure.gitlibs.dir") (System/getenv "GITLIBS"))]
       (if (str/blank? lib-dir)
         (jio/file (System/getProperty "user.home") ".gitlibs")
         (jio/file lib-dir))))

   :gitlibs/command
   (or (System/getProperty "clojure.gitlibs.command") (System/getenv "GITLIBS_COMMAND") "git")

   :gitlibs/debug
   (Boolean/parseBoolean (or (System/getProperty "clojure.gitlibs.debug") (System/getenv "GITLIBS_DEBUG") "false"))

   :gitlibs/terminal
   (Boolean/parseBoolean (or (System/getProperty "clojure.gitlibs.terminal") (System/getenv "GITLIBS_TERMINAL") "false"))})

(def CONFIG
  "Config map - deref to access"
  (delay (init-config)))