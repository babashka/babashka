(ns ^{:skip-wiki true}
  clojure.tools.deps.util.maven
  "BB-STAND-IN for the tools.deps namespace of the same name. The upstream
  one wraps Maven Resolver, this one carries the functions other tools.deps
  namespaces use, on babashka.impl.mvn."
  (:require [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.settings :as settings])
  (:import [org.apache.maven.settings Proxy Server Settings]))

(def standard-repos repo/standard-repos)

(defn- ->server
  ^Server [id {:keys [username password]}]
  (doto (Server.)
    (.setId id)
    (.setUsername username)
    (.setPassword password)))

(defn- ->proxy
  ^Proxy [{:keys [id active protocol host port username password non-proxy-hosts]}]
  (doto (Proxy.)
    (.setId id)
    (.setActive (boolean active))
    (.setProtocol protocol)
    (.setHost host)
    (.setPort (or port 0))
    (.setUsername username)
    (.setPassword password)
    (.setNonProxyHosts non-proxy-hosts)))

(defn get-settings
  "The user settings, as the Maven Settings that tools.deps returns. Read with
  babashka.impl.mvn.settings, which is also what the resolver itself uses."
  ^Settings []
  (let [{:keys [servers proxies]} (settings/read-settings)
        s (Settings.)]
    (doseq [[id server] servers]
      (.addServer s (->server id server)))
    (doseq [p proxies]
      (.addProxy s (->proxy p)))
    s))

(def default-local-repo repo/default-local-repo)

(def cached-local-repo
  (delay (repo/user-local-repo)))

(def lib->names coords/lib->names)

(def version-range? coords/version-range?)
