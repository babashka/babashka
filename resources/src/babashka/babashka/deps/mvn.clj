(ns babashka.deps.mvn
  "Maven concerns of babashka's tools.deps that tools.deps has no API for."
  (:require [babashka.impl.mvn.cipher :as cipher]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.settings :as settings]
            [babashka.impl.mvn.tools-deps :as mvn]))

(defn- check-credentials!
  [repositories]
  (doseq [[id {:keys [url username password]}] repositories
          :when (or username password)
          [k v] [[:url url] [:username username] [:password password]]
          :when (not (string? v))]
    (throw (ex-info (str "Repository " id " needs a string " k " to use its credentials")
                    {:repository id :key k}))))

(defn ^:no-doc with-repository-credentials*
  [repositories f]
  (check-credentials! repositories)
  (binding [repo/*caller-servers* (into {} (filter (fn [[_ r]] (:username r))) repositories)]
    (f)))

(defmacro with-repository-credentials
  "Evaluates body with the :username and :password of each entry of
  repositories applied to the tools.deps lookups in it.
  repositories is a map of repository id to :url, :username and :password, all
  strings. Entries without credentials are ignored.
  An entry applies only to the repository with its id and its :url.
  settings.xml wins for a repository id both name.
  An inner form replaces the repositories of an outer one.
  The lookups of a lazy seq realized outside body get no credentials.
  Throws if an entry has credentials and lacks :url, :username or :password."
  [repositories & body]
  `(with-repository-credentials* ~repositories (fn [] ~@body)))

(defn active-proxy
  "Returns the first active proxy of the user's Maven settings as a map of
  :host, :port, :protocol, :username, :password and :non-proxy-hosts, or nil
  if none is active. The password is decrypted. :non-proxy-hosts is not
  applied."
  []
  (when-let [p (first (filter :active (:proxies (settings/read-settings))))]
    (cond-> (select-keys p [:host :port :protocol :username :password :non-proxy-hosts])
      (:password p) (update :password cipher/decrypt-password {:server (str "proxy " (:host p))}))))

(defn model-repos
  "Returns the repositories of model as a map of repository id to :url.
  model is the return value of clojure.tools.deps.extensions.pom/read-model-file
  or read-model."
  [model]
  (mvn/model-repos model))
