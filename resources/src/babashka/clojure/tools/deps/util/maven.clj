(ns ^{:skip-wiki true}
  clojure.tools.deps.util.maven
  "babashka's stand-in for the tools.deps namespace of the same name. The
  upstream one wraps Maven Resolver, this one carries the functions other
  tools.deps namespaces use, on babashka.mvn."
  (:require [babashka.mvn.coords :as coords]
            [babashka.mvn.repo :as repo]
            [babashka.mvn.settings :as settings]))

(def standard-repos repo/standard-repos)

(defn get-settings
  "The user settings as a map, see babashka.mvn.settings."
  []
  (settings/read-settings))

(def default-local-repo repo/default-local-repo)

(def cached-local-repo
  (delay repo/default-local-repo))

(def lib->names coords/lib->names)

(def version-range? coords/version-range?)
