;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.tool
  "API for managing CLI tools. This code may eventually move out of tools.deps
  and into the CLI code base as it is specific to the CLI."
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.tools.deps.edn :as depsedn]
    [clojure.tools.deps.extensions :as ext]
    [clojure.tools.deps.util.io :as io])
  (:import
    [java.io File]))

(defn- tool-dir
  ^File []
  (jio/file (.getParentFile (jio/file (depsedn/user-deps-path))) "tools"))

(defn- tool-file
  "Create File location for tool name"
  ^File [tool]
  (jio/file (tool-dir) (str tool ".edn")))

(defn install-tool
  "Procure the lib+coord, install the tool to the user tools dir (with lib, coord)"
  [lib coord as]
  (let [{:keys [root user]} (depsedn/create-edn-maps {:project nil})
        master-edn (depsedn/merge-edns [root user])
        deps-info (ext/manifest-type lib coord master-edn)
        f (tool-file as)]
    ;; procure
    (ext/coord-paths lib (merge coord deps-info) (:deps/manifest deps-info) master-edn)
    ;; ensure tool dir
    (.mkdirs (.getParentFile f))
    ;; write tool file
    (spit f
      (with-out-str
        (binding [*print-namespace-maps* false
                  pprint/*print-right-margin* 100]
          (pprint/pprint {:lib lib :coord coord}))))))

(defn resolve-tool
  "Resolve a tool by name, look up and return:
  {:lib lib
   :coord coord}
  Or nil if unknown."
  [tool]
  (let [f (tool-file tool)]
    (when (.exists f)
      (io/slurp-edn f))))

(defn usage
  "Resolve a tool and return it's usage data, which may be nil.
  Throws ex-info if tool is unknown."
  [tool]
  (if-let [{:keys [lib coord]} (resolve-tool tool)]
    (let [{:keys [root user]} (depsedn/create-edn-maps {:project nil})
          config (depsedn/merge-edns [root user])
          [lib coord] (ext/canonicalize lib coord config)
          manifest-type (ext/manifest-type lib coord config)]
      (ext/coord-usage lib (merge coord manifest-type) (:deps/manifest manifest-type) config))
    (throw (ex-info (str "Unknown tool: " tool) {:tool tool}))))

(defn list-tools
  "Return seq of available tool names"
  []
  (->> (.listFiles (tool-dir))
    (filter #(.isFile ^File %))
    (map #(.getName ^File %))
    (filter #(str/ends-with? % ".edn"))
    (map #(subs % 0 (- (count %) 4)))
    sort))

(defn remove-tool
  "Removes tool installation, if it exists. Returns true if it exists and was deleted."
  [tool]
  (let [f (tool-file tool)]
    (when (.exists f)
      (.delete f))))