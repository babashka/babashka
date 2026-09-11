(ns babashka.impl.mvn.pom
  "POM parsing and the effective model: profiles, inheritance,
  interpolation, dependency management with BOM imports, relocation. After
  Maven's DefaultModelBuilder, for what dependency resolution needs; Apache
  License 2.0, see NOTICE.md."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.xml :as x]
            [clojure.string :as str]))

;; Parsing

(defn- gav [el]
  {:group (x/child-text el "groupId")
   :artifact (x/child-text el "artifactId")
   :version (x/child-text el "version")})

(defn- exclusion [el]
  (gav el))

(defn- dependency [el]
  {:group (x/child-text el "groupId")
   :artifact (x/child-text el "artifactId")
   :version (x/child-text el "version")
   :type (or (x/child-text el "type") "jar")
   :classifier (x/child-text el "classifier")
   :scope (x/child-text el "scope")
   :optional (x/child-text el "optional")
   :exclusions (mapv exclusion (some-> (x/child el "exclusions") (x/children "exclusion")))})

(defn- dependency-key* [{:keys [group artifact type classifier]}]
  [group artifact (or type "jar") classifier])

(defn- merge-duplicates
  "One dependency per key, as Maven's model normalizer leaves it: the last
  declaration wins, in the place of the first. Flattened POMs declare the
  same artifact more than once."
  [deps]
  (let [[order by-key]
        (reduce (fn [[order by-key] d]
                  (let [k (dependency-key* d)]
                    [(if (contains? by-key k) order (conj order k))
                     (assoc by-key k d)]))
                [[] {}]
                deps)]
    (mapv by-key order)))

(defn- parse-dependencies [el]
  (merge-duplicates (mapv dependency (x/children el "dependency"))))

(defn- properties [el]
  (into {} (for [p (x/elements el)]
             [(x/tag-name p) (or (x/text p) "")])))

(defn- repositories [el]
  (mapv (fn [r] {:id (x/child-text r "id") :url (x/child-text r "url")})
        (x/children el "repository")))

(defn- activation [el]
  (when el
    {:active-by-default (x/child-text el "activeByDefault")
     :jdk (x/child-text el "jdk")
     :os (when-let [os (x/child el "os")]
           {:name (x/child-text os "name")
            :family (x/child-text os "family")
            :arch (x/child-text os "arch")
            :version (x/child-text os "version")})
     :property (when-let [p (x/child el "property")]
                 {:name (x/child-text p "name")
                  :value (x/child-text p "value")})
     :file (when-let [f (x/child el "file")]
             {:exists (x/child-text f "exists")
              :missing (x/child-text f "missing")})}))

(defn- profile [el]
  {:id (x/child-text el "id")
   :activation (activation (x/child el "activation"))
   :properties (or (some-> (x/child el "properties") properties) {})
   :dependencies (or (some-> (x/child el "dependencies") parse-dependencies) [])
   :dependency-management (or (some-> (x/child el "dependencyManagement") (x/child "dependencies") parse-dependencies) [])
   :repositories (or (some-> (x/child el "repositories") repositories) [])})

(defn- build [el]
  (when el
    {:source-directory (x/child-text el "sourceDirectory")
     :resources (mapv #(x/child-text % "directory")
                      (some-> (x/child el "resources") (x/children "resource")))
     :plugins (mapv (fn [p]
                      {:group (x/child-text p "groupId")
                       :artifact (x/child-text p "artifactId")
                       :executions
                       (mapv (fn [e]
                               {:goals (mapv x/text (some-> (x/child e "goals") (x/children "goal")))
                                :sources (mapv x/text (some-> (x/child e "configuration") (x/child "sources") (x/children "source")))
                                ;; the plugin's <resource><directory>, or the
                                ;; bare text tools.deps reads
                                :resources (mapv #(or (x/child-text % "directory") (x/text %))
                                                 (some-> (x/child e "configuration") (x/child "resources") (x/children "resource")))})
                             (some-> (x/child p "executions") (x/children "execution")))})
                    (some-> (x/child el "plugins") (x/children "plugin")))}))

(defn parse
  "The raw model of a POM."
  [s]
  (let [root (x/parse s)
        parent (x/child root "parent")]
    (merge (gav root)
           {:packaging (or (x/child-text root "packaging") "jar")
            :parent (when parent
                      (assoc (gav parent) :relative-path (x/child-text parent "relativePath")))
            :properties (or (some-> (x/child root "properties") properties) {})
            :dependencies (or (some-> (x/child root "dependencies") parse-dependencies) [])
            :dependency-management (or (some-> (x/child root "dependencyManagement") (x/child "dependencies") parse-dependencies) [])
            :repositories (or (some-> (x/child root "repositories") repositories) [])
            :profiles (mapv profile (some-> (x/child root "profiles") (x/children "profile")))
            :licenses (mapv (fn [l] {:name (x/child-text l "name") :url (x/child-text l "url")})
                            (some-> (x/child root "licenses") (x/children "license")))
            :relocation (some-> (x/child root "distributionManagement") (x/child "relocation") gav)
            :build (build (x/child root "build"))})))

;; Profiles

(defn- negated [s]
  (if (str/starts-with? s "!") [true (subs s 1)] [false s]))

;; Activation, after Maven's profile activators. `props` looks a property
;; up by name: the JVM's, and the environment as env.NAME, what the Maven
;; CLI and MIMA hand the model builder.

(defn- jdk-tokens
  "The first three numbers of a JDK version the way Maven compares them:
  every character outside digits, dots, underscores and dashes dropped,
  split on those, padded with zeros. Throws on a non-number."
  [s]
  (let [s (str/replace s #"[^\d._-]" "")
        tokens (str/split s #"[._-]")]
    (mapv #(Integer/parseInt %) (take 3 (concat tokens (repeat "0"))))))

(defn- jdk-bound [token]
  (cond (str/starts-with? token "[") {:value (str/trim (subs token 1)) :closed true}
        (str/starts-with? token "(") {:value (str/trim (subs token 1)) :closed false}
        (str/ends-with? token "]") {:value (str/trim (subs token 0 (dec (count token)))) :closed true}
        (str/ends-with? token ")") {:value (str/trim (subs token 0 (dec (count token)))) :closed false}
        (= "" token) {:value "" :closed false}))

(defn- jdk-relation
  "-1, 0 or 1 for `version` against a range bound, Maven's getRelationOrder."
  [version {:keys [value closed]} left?]
  (if (= "" value)
    (if left? 1 -1)
    (let [c (compare (jdk-tokens version) (jdk-tokens value))]
      (cond (not (zero? c)) c
            (not closed) (if left? -1 1)
            :else 0))))

(defn- jdk-in-range? [version range]
  (let [bounds (keep jdk-bound (str/split range #","))
        [lower upper] (if (< (count bounds) 2)
                        (conj (vec bounds) {:value "99999999" :closed false})
                        bounds)
        left (jdk-relation version lower true)]
    (cond (zero? left) true
          (neg? left) false
          :else (<= (jdk-relation version upper false) 0))))

(defn- jdk-active?
  "Maven's JdkVersionProfileActivator: a `!` negates a prefix match, a
  range compares three numeric tokens, an unparsable version is inactive."
  [spec version]
  (cond (str/blank? version) false
        (str/starts-with? spec "!") (not (str/starts-with? version (subs spec 1)))
        (re-find #"^[\[(]" spec) (try (jdk-in-range? version spec)
                                      (catch NumberFormatException _ false))
        :else (str/starts-with? version spec)))

(def ^:private path-separator (System/getProperty "path.separator"))

(defn- os-family?
  "plexus-utils Os.isFamily for a lower-cased OS name; a family Maven does
  not know matches as a substring of the name."
  [family os-name]
  (case family
    "windows" (str/includes? os-name "windows")
    "os/2" (str/includes? os-name "os/2")
    "netware" (str/includes? os-name "netware")
    "dos" (and (= ";" path-separator)
               (not (os-family? "netware" os-name))
               (not (os-family? "windows" os-name))
               (not (os-family? "win9x" os-name)))
    "mac" (str/includes? os-name "mac")
    "tandem" (str/includes? os-name "nonstop_kernel")
    "unix" (and (= ":" path-separator)
                (not (os-family? "openvms" os-name))
                (or (not (os-family? "mac" os-name)) (str/ends-with? os-name "x")))
    "win9x" (and (os-family? "windows" os-name)
                 (some #(str/includes? os-name %) ["95" "98" "me" "ce"]))
    "z/os" (or (str/includes? os-name "z/os") (str/includes? os-name "os/390"))
    "os/400" (str/includes? os-name "os/400")
    "openvms" (str/includes? os-name "openvms")
    (str/includes? os-name family)))

(defn- os-match? [expected actual]
  (let [[not? v] (negated (str/lower-case expected))
        active (= v actual)]
    (if not? (not active) active)))

(defn- os-active?
  "Maven's OperatingSystemProfileActivator against the os.name, os.arch and
  os.version `props` give; a version may be `regex:` followed by a pattern."
  [{:keys [name family arch version]} props]
  (let [actual (fn [k default] (str/lower-case (or (props k) (System/getProperty k) default)))
        os-name (actual "os.name" "")
        os-arch (actual "os.arch" "")
        os-version (actual "os.version" "")]
    (and (or (some? name) (some? family) (some? arch) (some? version))
         (or (nil? family)
             (let [[not? f] (negated (str/lower-case family))
                   active (os-family? f os-name)]
               (if not? (not active) active)))
         (or (nil? name) (os-match? name os-name))
         (or (nil? arch) (os-match? arch os-arch))
         (or (nil? version)
             (if (str/starts-with? version "regex:")
               (some? (re-matches (re-pattern (subs version (count "regex:"))) os-version))
               (os-match? version os-version))))))

(defn- property-value [name]
  (if (str/starts-with? name "env.")
    (env/getenv (subs name 4))
    (System/getProperty name)))

(defn- property-active?
  "Maven's PropertyProfileActivator: with a value, that value (or its `!`
  negation) must equal the property's; without, the property must be
  non-empty, `!` on the name negating that."
  [{:keys [name value]} props]
  (let [[not-name? name] (negated (or name ""))]
    (if (str/blank? name)
      false
      (let [actual (props name)]
        (if (and value (pos? (count value)))
          (let [[not-value? v] (negated value)
                eq (= v actual)]
            (if not-value? (not eq) eq))
          (let [present (and (some? actual) (pos? (count actual)))]
            (if not-name? (not present) present)))))))

(defn- file-active? [{:keys [exists missing]} basedir]
  (when basedir
    (let [path #(str/replace % #"\$\{(project\.)?basedir\}" (str basedir))
          f (fn [p] (let [p (path p)] (if (fs/absolute? p) p (str (fs/path basedir p)))))]
      (cond
        exists (fs/exists? (f exists))
        missing (not (fs/exists? (f missing)))
        :else false))))

(defn- explicitly-active? [{:keys [activation]} basedir props]
  (when activation
    (let [{:keys [jdk os property file]} activation]
      (and (or jdk os property file)
           (or (nil? jdk) (jdk-active? jdk (props "java.version")))
           (or (nil? os) (os-active? os props))
           (or (nil? property) (property-active? property props))
           (or (nil? file) (file-active? file basedir))))))

(defn- active-profiles
  "Profiles activated by jdk, os, property or file. When none is, the
  activeByDefault ones."
  [{:keys [profiles]} basedir]
  (let [explicit (filter #(explicitly-active? % basedir property-value) profiles)]
    (if (seq explicit)
      explicit
      (filter #(= "true" (get-in % [:activation :active-by-default])) profiles))))

;; Merging, after ModelMerger. Target entries keep their place, source
;; entries with new keys are appended. A dominant source replaces target
;; entries with the same key.

(defn- dependency-key [{:keys [group artifact type classifier]}]
  [group artifact (or type "jar") classifier])

(defn- merge-by-key [target source key-fn dominant?]
  (let [keys-in-target (set (map key-fn target))
        target (if dominant?
                 (let [replacements (into {} (map (juxt key-fn identity)) source)]
                   (mapv #(get replacements (key-fn %) %) target))
                 target)]
    (into target (remove #(keys-in-target (key-fn %))) source)))

(defn- inject-profile [model {:keys [properties dependencies dependency-management repositories]}]
  (-> model
      (update :properties merge properties)
      (update :dependencies merge-by-key dependencies dependency-key true)
      (update :dependency-management merge-by-key dependency-management dependency-key true)
      (update :repositories merge-by-key repositories :id true)))

(defn- inject-profiles [model basedir]
  (reduce inject-profile model (active-profiles model basedir)))

(defn- inherit [child parent]
  (-> child
      (update :group #(or % (:group parent)))
      (update :version #(or % (:version parent)))
      (assoc :properties (merge (:properties parent) (:properties child)))
      (update :dependencies merge-by-key (:dependencies parent) dependency-key false)
      (update :dependency-management merge-by-key (:dependency-management parent) dependency-key false)
      (update :repositories merge-by-key (:repositories parent) :id false)
      (update :licenses #(if (seq %) % (:licenses parent)))))

;; Interpolation

(defn- model-values [{:keys [group artifact version packaging parent]} basedir]
  (let [with-prefixes (fn [k v] (when v {(str "project." k) v (str "pom." k) v k v}))]
    (merge (with-prefixes "groupId" group)
           (with-prefixes "artifactId" artifact)
           (with-prefixes "version" version)
           (with-prefixes "packaging" packaging)
           (when parent
             {"project.parent.groupId" (:group parent)
              "project.parent.artifactId" (:artifact parent)
              "project.parent.version" (:version parent)
              "parent.version" (:version parent)
              "parent.groupId" (:group parent)})
           (when basedir
             {"basedir" (str basedir) "project.basedir" (str basedir)}))))

(defn- interpolator [model basedir]
  (let [values (merge (:properties model) (model-values model basedir))
        lookup (fn [k] (or (get values k) (property-value k)))]
    (fn interpolate [s]
      (if (and (string? s) (str/includes? s "${"))
        (loop [s s depth 0]
          (let [s' (str/replace s #"\$\{([^}]+)\}"
                                (fn [[whole k]] (let [v (lookup k)] (if (some? v) (str v) whole))))]
            (if (or (= s s') (>= depth 10))
              s'
              (recur s' (inc depth)))))
        s))))

(defn- interpolate-dependency [f d]
  (-> d
      (update :group f) (update :artifact f) (update :version f)
      (update :type f) (update :classifier f) (update :scope f) (update :optional f)
      (update :exclusions #(mapv (fn [e] (-> e (update :group f) (update :artifact f))) %))))

(defn- interpolate-model [model basedir]
  (let [f (interpolator model basedir)]
    (-> model
        (update :group f) (update :artifact f) (update :version f) (update :packaging f)
        (update :properties #(into {} (map (fn [[k v]] [k (f v)])) %))
        (update :dependencies #(mapv (partial interpolate-dependency f) %))
        (update :dependency-management #(mapv (partial interpolate-dependency f) %))
        (update :repositories #(mapv (fn [r] (update r :url f)) %))
        (update :licenses #(mapv (fn [l] (-> l (update :name f) (update :url f))) %))
        (update :relocation #(when % (-> % (update :group f) (update :artifact f) (update :version f)))))))

;; Effective model

(defn- import? [{:keys [scope type]}]
  (and (= "import" scope) (= "pom" type)))

(declare effective-model)

(defn- gav-key [{:keys [group artifact version]}]
  [group artifact version])

(defn- lineage
  "The raw models with profiles injected, child first, up the parent chain."
  [raw {:keys [read-pom basedir cache]}]
  (loop [model (inject-profiles raw basedir)
         acc []
         seen #{}]
    (let [acc (conj acc model)
          {:keys [parent]} model]
      (if (and parent (not (seen (gav-key parent))))
        (let [parent-raw (or (get @cache [:raw (gav-key parent)])
                             (some-> (read-pom parent (:repositories model)) parse))]
          (when-not parent-raw
            (throw (ex-info (str "Could not find parent POM " (:group parent) ":" (:artifact parent) ":" (:version parent))
                            {:parent parent})))
          (swap! cache assoc [:raw (gav-key parent)] parent-raw)
          (recur (inject-profiles parent-raw nil) acc (conj seen (gav-key parent))))
        acc))))

(defn- import-managed
  "dependencyManagement with import-scoped BOMs replaced by their managed
  dependencies. Entries already present win, earlier imports win over later."
  [managed ctx repositories]
  (reduce (fn [acc dep]
            (if (import? dep)
              (let [{:keys [read-pom]} ctx
                    bom (some-> (read-pom dep repositories) parse)]
                (when-not bom
                  (throw (ex-info (str "Could not find BOM " (:group dep) ":" (:artifact dep) ":" (:version dep))
                                  {:bom dep})))
                (merge-by-key acc
                              (:dependency-management (effective-model bom (assoc ctx :basedir nil)))
                              dependency-key false))
              (conj acc dep)))
          []
          managed))

(defn- apply-management [deps managed]
  (let [by-key (into {} (map (juxt dependency-key identity)) managed)]
    (mapv (fn [dep]
            (if-let [m (get by-key (dependency-key dep))]
              (-> dep
                  (update :version #(or % (:version m)))
                  (update :scope #(or % (:scope m)))
                  (update :optional #(or % (:optional m)))
                  (update :exclusions #(if (seq %) % (:exclusions m))))
              dep))
          deps)))

(defn effective-model
  "The effective model for a raw one. ctx: :read-pom, a function of a gav
  map and the repositories the POM declares that returns POM text or nil,
  :cache, an atom, and :basedir for a local POM."
  [raw {:keys [cache basedir] :as ctx}]
  (let [k [:effective (gav-key raw) basedir]]
    (or (get @cache k)
        (let [models (lineage raw ctx)
              assembled (reduce inherit (first models) (rest models))
              model (interpolate-model assembled basedir)
              managed (import-managed (:dependency-management model) ctx (:repositories model))
              model (-> model
                        (assoc :dependency-management managed)
                        (update :dependencies apply-management managed))]
          (swap! cache assoc k model)
          model))))

(defn dependencies
  "The dependencies of an effective model, scope defaulted, optional as a
  boolean."
  [model]
  (mapv (fn [{:keys [scope optional] :as d}]
          (assoc d
                 :scope (or scope "compile")
                 :optional (= "true" optional)))
        (:dependencies model)))
