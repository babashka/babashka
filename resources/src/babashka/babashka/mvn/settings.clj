(ns babashka.mvn.settings
  "The parts of ~/.m2/settings.xml that resolution needs."
  (:require [babashka.fs :as fs]
            [babashka.mvn.xml :refer [child child-text children elements text]]
            [clojure.string :as str]))

(defn interpolate
  "Replaces ${env.NAME}, ${user.home} and other system properties in s."
  [s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[whole key]]
                   (or (when (str/starts-with? key "env.")
                         (System/getenv (subs key 4)))
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
  (let [root (babashka.mvn.xml/parse s)]
    {:local-repository (interpolate (child-text root "localRepository"))
     :servers (into {} (map server (some-> (child root "servers") (children "server"))))
     :mirrors (mapv mirror (some-> (child root "mirrors") (children "mirror")))
     :proxies (mapv proxy-entry (some-> (child root "proxies") (children "proxy")))
     :profiles (into {} (map profile (some-> (child root "profiles") (children "profile"))))
     :active-profiles (mapv text (some-> (child root "activeProfiles") (children "activeProfile")))}))

(def user-settings-file
  (str (fs/path (System/getProperty "user.home") ".m2" "settings.xml")))

(defn read-settings
  "The user settings, or an empty map when there is no settings.xml."
  []
  (if (fs/exists? user-settings-file)
    (parse (slurp user-settings-file))
    {}))

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
