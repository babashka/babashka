;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.jar
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.util.file :as file]
    [clojure.tools.build.util.zip :as zip]
    [clojure.string :as str])
  (:import
    [java.util.jar Manifest JarOutputStream]))

(defn jar
  [{mf-attrs :manifest, :keys [class-dir jar-file main] :as params}]
  (let [jar-file (api/resolve-path jar-file)
        class-dir-file (file/ensure-dir (api/resolve-path class-dir))
        mf-attr-strs (reduce-kv (fn [m k v] (assoc m (str k) (str v))) nil mf-attrs)]
    (file/ensure-dir (.getParent jar-file))
    (let [manifest (Manifest.)]
      (zip/fill-manifest! manifest
        (merge
          (cond->
            {"Manifest-Version" "1.0"
             "Created-By" "org.clojure/tools.build"
             "Build-Jdk-Spec" (System/getProperty "java.specification.version")}
            main (assoc "Main-Class" (str/replace (str main) \- \_)))
          mf-attr-strs))
      (with-open [jos (JarOutputStream. (jio/output-stream jar-file) manifest)]
        (zip/copy-to-zip jos class-dir-file)))))
