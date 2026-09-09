(ns ^{:skip-wiki true}
  clojure.tools.deps.util.maven
  "BB-STAND-IN for the tools.deps namespace of the same name. The upstream
  one wraps Maven Resolver, this one carries the functions other tools.deps
  namespaces use, on babashka.impl.mvn."
  (:require [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.settings :as settings]))

(def standard-repos repo/standard-repos)

(defn get-settings
  "The user settings as a map, see babashka.impl.mvn.settings."
  []
  (settings/read-settings))

(def default-local-repo repo/default-local-repo)

(def cached-local-repo
  (delay repo/default-local-repo))

(def lib->names coords/lib->names)

(def version-range? coords/version-range?)
