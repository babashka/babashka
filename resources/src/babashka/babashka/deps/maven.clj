(ns babashka.deps.maven
  "Maven versions, repository credentials, proxy settings and POM
  repositories for tools.deps."
  (:require [babashka.impl.mvn.cipher :as cipher]
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
  (binding [mvn/*caller-servers* (into {} (filter (fn [[_ r]] (:username r))) repositories)]
    (f)))

(defmacro with-repository-credentials
  "Evaluates body with the credentials in repositories applied to its
  tools.deps lookups.
  repositories is a map of repository id to :url, :username and :password,
  each a string.
  An entry applies only to the repository with its id and :url.
  A server in settings.xml for the same id wins.
  An entry without :username and :password is ignored.
  An inner form replaces the credentials of an outer one.
  The credentials apply on the thread of body and on threads that convey
  bindings, such as future and pmap.
  A lazy seq realized after body returns looks up without them.
  Throws if an entry with :username or :password lacks one of the three."
  [repositories & body]
  `(with-repository-credentials* ~repositories (fn [] ~@body)))

(defn active-proxy
  "Returns the first active proxy in the user's Maven settings as a map of
  :host, :port, :protocol, :username, :password and :non-proxy-hosts, or nil
  if none is active.
  :port is 8080 and :protocol is http if settings.xml names neither.
  :username, :password and :non-proxy-hosts are left out if absent.
  The password is decrypted.
  :non-proxy-hosts is returned as written and not applied."
  []
  (when-let [p (first (filter :active (:proxies (settings/read-settings))))]
    (cond-> (into {} (filter val) (select-keys p [:host :port :protocol :username :password :non-proxy-hosts]))
      (:password p) (update :password cipher/decrypt-password {:server (str "proxy " (:host p))}))))

(defn find-versions
  "Returns the release versions of lib in the repositories of config and the
  local repository, oldest first, as a vector of {:mvn/version version}, or
  nil if they list none.
  config is a tools.deps config with :mvn/repos and an optional
  :mvn/local-repo.
  Includes snapshot versions if opts has :snapshots true.
  Returns [] if they list only snapshot versions and opts excludes them."
  ([lib config] (find-versions lib config {}))
  ([lib config {:keys [snapshots]}]
   (mvn/find-versions lib config (boolean snapshots))))

(defn model-repos
  "Returns the repositories of model as :mvn/repos data, a map of repository
  id to :url and the :releases and :snapshots policies the POM names.
  model is the result of clojure.tools.deps.extensions.pom/read-model-file
  or read-model.
  Includes central at https://repo.maven.apache.org/maven2 unless the model
  names central."
  [model]
  (mvn/model-repos model))
