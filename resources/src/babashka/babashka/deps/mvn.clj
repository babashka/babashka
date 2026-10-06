(ns babashka.deps.mvn
  "Maven versions, repository credentials, proxy settings and POM
  repositories for tools.deps."
  (:require [babashka.impl.mvn.cipher :as cipher]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.settings :as settings]
            [babashka.impl.mvn.tools-deps :as mvn]))

(defn- check-credentials! [repositories]
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
  "Evaluates body with the credentials of repositories applied to the
  tools.deps lookups in body, on every thread body starts.
  repositories is a map of repository id to :url, :username and :password,
  all strings, the shape of :mvn/repos with Leiningen's credential keys.
  Entries without :username and :password are ignored.
  An entry applies only to the repository with its id and its :url.
  A server in settings.xml wins for an id both name.
  An inner form replaces the repositories of an outer one.
  A lazy seq realized outside body looks up without the credentials.
  Throws if an entry has :username or :password and lacks one of the three."
  [repositories & body]
  `(with-repository-credentials* ~repositories (fn [] ~@body)))

(defn active-proxy
  "Returns the first active proxy in the user's Maven settings as a map of
  :host, :port, :protocol, :username, :password and :non-proxy-hosts, each
  where settings.xml names it, or nil if none is active.
  The password is decrypted.
  :non-proxy-hosts is returned as written and not applied."
  []
  (when-let [p (first (filter :active (:proxies (settings/read-settings))))]
    (cond-> (into {} (filter val) (select-keys p [:host :port :protocol :username :password :non-proxy-hosts]))
      (:password p) (update :password cipher/decrypt-password {:server (str "proxy " (:host p))}))))

(defn find-versions
  "Returns the versions of lib in the repositories of config, snapshots
  included, oldest first, as a vector of {:mvn/version version}.
  config is a tools.deps config with :mvn/repos and an optional
  :mvn/local-repo.
  clojure.tools.deps.extensions/find-versions returns the same without
  snapshots."
  [lib config]
  (mvn/all-versions lib config))

(defn model-repos
  "Returns the repositories of model as :mvn/repos data, a map of repository
  id to :url and the :releases and :snapshots policies the POM names.
  model is the result of clojure.tools.deps.extensions.pom/read-model-file
  or read-model.
  Includes central at https://repo.maven.apache.org/maven2, last, unless the
  model names central."
  [model]
  (mvn/model-repos model))
