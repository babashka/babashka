;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.create-basis
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.deps :as deps]
    [clojure.tools.deps.util.dir :as dir]))

(defn create-basis
  "Wrapper for deps/create-basis, but ensure relative paths are resolved
  relative to *project-root*.

    Options (note, paths resolved via *project-root*):
    :root    - dep source, default = :standard
    :user    - dep source, default = nil
    :project - dep source, default = :standard (\"./deps.edn\")
    :extra   - dep source, default = nil
    :aliases - coll of aliases of argmaps to apply to subprocesses"
  ([]
   (create-basis nil))
  ([params]
   (dir/with-dir (jio/file api/*project-root*)
     (deps/create-basis (merge {:user nil} params)))))
