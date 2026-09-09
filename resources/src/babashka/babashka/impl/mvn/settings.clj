(ns babashka.impl.mvn.settings
  "The parts of ~/.m2/settings.xml that resolution needs. Mirror and proxy
  selection after Maven's DefaultMirrorSelector and DefaultProxySelector,
  Apache License 2.0, see NOTICE.md."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.xml :refer [child child-text children elements text]]
            [clojure.string :as str]))

(defn interpolate
  "Replaces ${env.NAME}, ${user.home} and other system properties in s."
  [s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[whole key]]
                   (or (when (str/starts-with? key "env.")
                         (env/getenv (subs key 4)))
                       (System/getProperty key)
                       whole)))))

(defn- server [el]
  [(child-text el "id")
   {:username (interpolate (child-text el "username"))
    :password (interpolate (child-text el "password"))}])

(defn- mirror [el]
  {:id (child-text el "id")
   :url (interpolate (child-text el "url"))
   :mirror-of (child-text el "mirrorOf")})

(defn- proxy-entry [el]
  {:id (child-text el "id")
   :active (not= "false" (child-text el "active"))
   :protocol (or (child-text el "protocol") "http")
   :host (interpolate (child-text el "host"))
   :port (some-> (child-text el "port") parse-long)
   :username (interpolate (child-text el "username"))
   :password (interpolate (child-text el "password"))
   :non-proxy-hosts (child-text el "nonProxyHosts")})

(defn- repository [el]
  {:id (child-text el "id")
   :url (interpolate (child-text el "url"))})

(defn- profile [el]
  [(child-text el "id")
   {:active-by-default (= "true" (some-> (child el "activation") (child-text "activeByDefault")))
    :repositories (mapv repository (some-> (child el "repositories") (children "repository")))
    :properties (into {} (for [p (some-> (child el "properties") elements)]
                           [(name (:tag p)) (text p)]))}])

(defn parse
  "Settings from an XML string."
  [s]
  (let [root (babashka.impl.mvn.xml/parse s)]
    {:local-repository (interpolate (child-text root "localRepository"))
     :servers (into {} (map server (some-> (child root "servers") (children "server"))))
     :mirrors (mapv mirror (some-> (child root "mirrors") (children "mirror")))
     :proxies (mapv proxy-entry (some-> (child root "proxies") (children "proxy")))
     :profiles (into {} (map profile (some-> (child root "profiles") (children "profile"))))
     :active-profiles (mapv text (some-> (child root "activeProfiles") (children "activeProfile")))}))

(defn- user-settings-file
  "~/.m2/settings.xml, read from user.home at call time."
  []
  (str (fs/path (System/getProperty "user.home") ".m2" "settings.xml")))

(defn read-settings
  "The user settings, or an empty map when there is no settings.xml."
  []
  (let [f (user-settings-file)]
    (if (fs/exists? f)
      (parse (slurp f))
      {})))

;; Mirror matching, after Maven's DefaultMirrorSelector.

(defn- external? [{:keys [url]}]
  (let [u (str/lower-case (or url ""))]
    (not (or (str/starts-with? u "file:")
             (str/includes? u "localhost")
             (str/includes? u "127.0.0.1")))))

(defn- matches-pattern? [pattern {:keys [id] :as repo}]
  (let [patterns (map str/trim (str/split pattern #","))
        excluded? (some #(and (str/starts-with? % "!") (= id (subs % 1))) patterns)]
    (boolean
     (and (not excluded?)
          (some (fn [p]
                  (or (= id p)
                      (= "*" p)
                      (and (= "external:*" p) (external? repo))
                      (and (= "external:http:*" p)
                           (external? repo)
                           (str/starts-with? (str/lower-case (:url repo)) "http:"))))
                patterns)))))

(defn mirror-for
  "The first mirror whose mirrorOf matches repo, or nil."
  [mirrors repo]
  (first (filter #(matches-pattern? (or (:mirror-of %) "") repo) mirrors)))

;; Proxies, after Maven's DefaultProxySelector and the JVM's http.proxyHost
;; properties that deps.clj used to pass to the java it spawned.

(defn- non-proxy-host?
  "Whether host matches one of the patterns: * is a wildcard, the match is
  case-insensitive. Maven's nonProxyHosts and Java's http.nonProxyHosts
  read the same way."
  [patterns host]
  (boolean
   (some (fn [pattern]
           (let [re (-> (java.util.regex.Pattern/quote pattern)
                        (str/replace "*" "\\E.*\\Q"))]
             (re-matches (re-pattern (str "(?i)" re)) host)))
         patterns)))

(defn- split-patterns [s re]
  (->> (str/split (or s "") re) (map str/trim) (remove str/blank?)))

(defn- env-proxy
  "The proxy for protocol from http_proxy or https_proxy, either case:
  scheme://[user:pass@]host:port. nil without a numeric port, as deps.clj
  reads them."
  [protocol]
  (when-let [value (or (env/getenv (str protocol "_proxy"))
                       (env/getenv (str/upper-case (str protocol "_proxy"))))]
    (let [uri (try (java.net.URI. value) (catch Exception _ nil))]
      (when (and uri (.getHost uri) (pos? (.getPort uri)))
        (let [[user pass] (some-> (.getUserInfo uri) (str/split #":" 2))]
          (cond-> {:host (.getHost uri) :port (.getPort uri)}
            (and user pass) (assoc :username user :password pass)))))))

(defn proxy-for
  "Returns the first active proxy for the URL protocol, respecting
  nonProxyHosts. Falls back to http_proxy or https_proxy and no_proxy.
  Returns nil for a direct connection, or a map with :host and :port.
  Includes :username and :password when configured."
  [{:keys [proxies]} url]
  (let [uri (java.net.URI. url)
        protocol (.getScheme uri)
        host (.getHost uri)]
    (when host
      (if-let [p (first (filter #(and (:active %) (= (:protocol %) protocol)) proxies))]
        (when-not (non-proxy-host? (split-patterns (:non-proxy-hosts p) #"\|") host)
          (cond-> {:host (:host p) :port (:port p)}
            (:username p) (assoc :username (:username p) :password (:password p))))
        (when-let [p (env-proxy protocol)]
          (when-not (non-proxy-host? (split-patterns (or (env/getenv "no_proxy")
                                                         (env/getenv "NO_PROXY"))
                                                     #",")
                                     host)
            p))))))

(defn active-profile-repositories
  "Repositories from profiles that are active by default or listed in
  activeProfiles."
  [{:keys [profiles active-profiles]}]
  (let [active (set active-profiles)]
    (into []
          (mapcat (fn [[id {:keys [active-by-default repositories]}]]
                    (when (or active-by-default (active id))
                      repositories)))
          profiles)))
