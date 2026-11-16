(ns cider.nrepl.middleware.ns
  (:refer-clojure :exclude [ns-aliases])
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [cider.nrepl.middleware.util.meta :as um]
   [orchard.cljs.analysis :as cljs-analysis]
   [clojure.java.io :as io]
   [clojure.string :as str]
   ;; BB-PATCH compliment does not run in sci
   #_[compliment.utils :as compliment.utils]
   [orchard.info :as info]
   [orchard.java.classpath :as cp]
   [orchard.misc :as misc]
   [orchard.namespace :as ns]
   [orchard.ns-form :as ns-form]
   [orchard.query :as query]
   [orchard.util.io :as util.io])
  (:import (clojure.lang Compiler IRecord IType LineNumberingPushbackReader Namespace RT Var) (java.io File StringReader) (java.net URL) (java.nio.file Files)))

(defn ns-list-vars-by-name
  "Return a list of vars named `name` amongst all namespaces.
  `name` is a symbol."
  [name]
  (->> (mapcat ns-interns (all-ns))
       (filter #(= (first %) name))
       (map second)))

(defn ns-vars-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map (comp str :name meta))
       sort))

(defn ns-vars-with-meta-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map meta)
       (map (juxt (comp str :name) um/relevant-meta))
       (into (sorted-map))))

(defn ns-list-cljs [env]
  (->> (cljs-analysis/all-ns env)
       keys
       (map name)
       sort))

(defn ns-vars-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         keys
         (map name)
         sort)))

(defn ns-vars-with-meta-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         (misc/update-vals (comp um/relevant-meta :meta))
         (misc/update-keys name)
         (into (sorted-map)))))

(defn ns-list [{:keys [filter-regexps] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns/loaded-namespaces filter-regexps)))

(defn ns-vars [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns var-query)
    (ns-vars-clj ns var-query)))

(defn ns-vars-with-meta [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-with-meta-cljs cljs-env ns var-query)
    (ns-vars-with-meta-clj ns var-query)))

(defn ns-path [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    ;; BB-PATCH bb has no cljs env, and its orchard.info has no info*
    nil #_(:file (info/info* {:dialect :cljs
                        :env cljs-env
                        :sym (symbol ns)}))
    (str (ns/canonical-source ns))))

(defn ns-list-reply [msg]
  {:ns-list (ns-list msg)})

(defn ns-list-vars-by-name-reply [{:keys [name]}]
  {:var-list (pr-str (ns-list-vars-by-name (symbol name)))})

(defn ns-vars-reply
  [msg]
  {:ns-vars (ns-vars msg)})

(defn ns-vars-with-meta-reply
  [msg]
  {:ns-vars-with-meta (ns-vars-with-meta msg)})

(defn- ns-path-reply [msg]
  (let [v (ns-path msg)
        cljs? (cljs/grab-cljs-env msg)]
    {:path v
     :url (if cljs?
            (or (some-> v io/resource str)
                v)
            v)}))

(defn- ns-load-all-reply
  [_msg]
  {:loaded-ns (ns/load-project-namespaces)})

(defn- ns-aliases-clj [ns]
  (->> (symbol ns)
       (clojure.core/ns-aliases)
       (misc/update-vals ns-name)
       (util/transform-value)))

(defn- ns-aliases-cljs [env ns]
  (->> (cljs-analysis/ns-aliases env ns)
       (remove (fn [[k v]] (= k v)))
       (into {})
       (util/transform-value)))

(defn ns-aliases [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-aliases-cljs cljs-env ns)
    (ns-aliases-clj ns)))

(defn- ns-aliases-reply [msg]
  {:ns-aliases (ns-aliases msg)})

;;; Resolving missing symbols
;;
;; `cider/resolve-missing` lists what could provide a symbol the current
;; namespace can't resolve yet: namespaces with a public var by that name, to
;; require, and classes by that name, to import.  A symbol that already resolves
;; gets nothing, since the other namespaces with a var by that name would only
;; change what it means.

(defn- split-symbol
  "Split the symbol string `s` into its qualifier (nil when unqualified) and its
  name.  A trailing dot, as in a constructor call, is dropped."
  [^String s]
  (let [s (if (and (.endsWith s ".") (> (count s) 1))
            (subs s 0 (dec (count s)))
            s)
        i (.indexOf s "/")]
    (if (and (pos? i) (< i (dec (count s))))
      [(subs s 0 i) (subs s (inc i))]
      [nil s])))

(defn- clojure-type?
  "Whether `c` was made by `deftype` or `defrecord`."
  [^Class c]
  (or (not (instance? Class c)) ;; BB-PATCH a deftype or defrecord type in sci is no Class
      (.isAssignableFrom IType c)
      (.isAssignableFrom IRecord c)))

(defn- defining-ns
  "The name of the namespace that defines the type `c`."
  [^Class c]
  (let [class-name (.getName c)
        package (subs class-name 0 (max 0 (.lastIndexOf class-name ".")))]
    (or (some #(let [ns-str (str (ns-name %))]
                 (when (= package (munge ns-str)) ns-str))
              (all-ns))
        (Compiler/demunge package))))

(defn- jdk-internal?
  "Whether `class-name` belongs to the JDK's own implementation."
  [^String class-name]
  (or (.startsWith class-name "jdk.internal.")
      (.startsWith class-name "sun.")
      (and (.startsWith class-name "com.sun.")
           (.contains class-name ".internal."))))

(defn- class-candidates
  "The classes named `short-name`.  Besides the classes on the classpath, this
  covers the ones loaded namespaces import, which includes those made by
  `deftype` and `defrecord`."
  [^String short-name]
  (when-not (str/includes? short-name ".")
    (let [sym (symbol short-name)
          loaded (into #{}
                       (comp (remove ns/inlined-dependency?)
                             (keep #(let [m #_(.getMapping ^Namespace % sym) ;; BB-PATCH a sci namespace is not a clojure.lang.Namespace
(get (ns-map %) sym)]
                                      (when (class? m) m))))
                       (all-ns))
          loaded-names (into #{} (map #(.getName ^Class %)) loaded)
          suffix (str "." short-name)]
      (concat
       (for [^Class c loaded]
         (if (clojure-type? c)
           {:name (.getName c) :type "type" :ns (defining-ns c)}
           {:name (.getName c) :type "class"}))
       ;; BB-PATCH only the loaded classes, bb scans no classpath
       (for [^String c nil #_(compliment.utils/classes-on-classpath)
             :when (and (.endsWith c suffix)
                        (not (loaded-names c))
                        (not (jdk-internal? c)))]
         {:name c :type "class"})))))

(defn- var-candidates
  "The loaded namespaces with a public var named `var-name`."
  [var-name]
  (let [sym (symbol var-name)]
    (for [ns (all-ns)
          :let [v #_(.findInternedVar ^Namespace ns sym) ;; BB-PATCH
(get (ns-interns ns) sym)]
          :when (and v #_(.isPublic ^Var v) ;; BB-PATCH
(not (:private (meta v))) (not (ns/inlined-dependency? ns)))]
      {:name (str (ns-name ns)) :type "ns"})))

(defn- class-named
  "The class named `class-name`, or nil.  Unlike `ns-resolve`, this doesn't
  initialize the class, which would run its static initializer."
  [^String class-name]
  (try #_(RT/classForNameNonLoading class-name) ;; BB-PATCH resolves only the classes bb has
(let [c (resolve (symbol class-name))] (when (class? c) c))
       ;; e.g. NoClassDefFoundError for a name in the wrong case
       (catch Exception _)
       (catch LinkageError _)))

(defn- named-ns
  "The namespace a qualifier names in the namespace `the-ns` without
  depending on what else got loaded: an alias, `the-ns` itself or
  `clojure.core`."
  [^Namespace the-ns q]
  (or #_(.lookupAlias the-ns q) ;; BB-PATCH
(get (clojure.core/ns-aliases the-ns) q)
      (when (#{(ns-name the-ns) 'clojure.core} q)
        (find-ns q))))

(defn- clj-resolves?
  "Whether `qualifier`/`var-name` already means something in the namespace
  `the-ns`."
  [^Namespace the-ns qualifier var-name]
  (let [sym (symbol var-name)]
    (if qualifier
      (let [q (symbol qualifier)]
        (if-let [^Namespace target (named-ns the-ns q)]
          (some? #_(.findInternedVar target sym) ;; BB-PATCH
(get (ns-interns target) sym))
          ;; a member of a class the namespace can use
          (class? (if (str/includes? qualifier ".")
                    (class-named qualifier)
                    #_(.getMapping the-ns q) ;; BB-PATCH
(get (ns-map the-ns) q)))))
      (or (special-symbol? sym)
          (some? #_(.getMapping the-ns sym) ;; BB-PATCH
(get (ns-map the-ns) sym))))))

(defn- resolve-missing-clj [ns-sym qualifier var-name]
  (let [the-ns (some-> ns-sym find-ns)]
    (when-not (and the-ns (clj-resolves? the-ns qualifier var-name))
      (let [qualifier-classes (when qualifier (class-candidates qualifier))]
        (concat qualifier-classes
                ;; After a slash comes a member, not a class.
                (when-not qualifier (class-candidates var-name))
                ;; A class before the slash makes it a static member.
                (when-not (seq qualifier-classes)
                  (var-candidates var-name)))))))

(defn- cljs-defines?
  "Whether the ClojureScript namespace `ns-sym` defines `sym`, as a var or as a
  macro in the Clojure namespace of the same name."
  [env ns-sym sym]
  (boolean
   (when ns-sym
     (or (get-in (cljs-analysis/find-ns env ns-sym) [:defs sym])
         (some-> ^Namespace (find-ns ns-sym) (.findInternedVar sym) .isMacro)))))

(defn- cljs-resolves?
  "Whether `qualifier`/`sym` already means something in the ClojureScript
  namespace `current`."
  [env current qualifier sym]
  (if qualifier
    (let [q (symbol qualifier)]
      (or (cljs-defines? env (cljs-analysis/ns-alias env q current) sym)
          (cljs-defines? env (cljs-analysis/macro-ns-alias env q current) sym)
          (cljs-defines? env (if (= q 'clojure.core) 'cljs.core (#{current 'cljs.core} q)) sym)))
    (let [ns-info (cljs-analysis/find-ns env current)]
      (or (special-symbol? sym)
          (contains? (cljs-analysis/referred-vars env current) sym)
          (contains? (cljs-analysis/referred-macros env current) sym)
          (contains? (:renames ns-info) sym)
          (contains? (:rename-macros ns-info) sym)
          (cljs-defines? env current sym)
          (contains? (cljs-analysis/core-vars env current) sym)
          (contains? (cljs-analysis/core-macros env current) sym)))))

(defn- resolve-missing-cljs [env current qualifier var-name]
  (let [sym (symbol var-name)]
    (when-not (and current
                   (cljs-analysis/find-ns env current)
                   (cljs-resolves? env current qualifier sym))
      (for [[ns-sym ns-info] (cljs-analysis/all-ns env)
            ;; A Node REPL's compiler env has a namespace without a name.
            :when (and ns-sym (not (#{'cljs.core current} ns-sym)))
            :let [v (get-in ns-info [:defs sym])
                  ;; Macros live in the Clojure namespace of the same name.
                  ^Var m (some-> ^Namespace (find-ns ns-sym) (.findInternedVar sym))
                  candidate-type (cond
                                   (and v (not (:private v)) (not (:anonymous v))) "ns"
                                   (and m (.isMacro m) (.isPublic m)) "macro")]
            :when candidate-type]
        {:name (str ns-sym) :type candidate-type}))))

(defn resolve-missing
  "The namespaces and classes that could provide the `:symbol` of `msg` in its
  `:ns`, as maps with a `:name` and a `:type` (`ns`, `macro`, `class` or
  `type`).  A `type` candidate also has the `:ns` that defines it."
  [{sym :symbol ns-str :ns :as msg}]
  (when (str/blank? sym)
    (throw (IllegalArgumentException. "No symbol to resolve")))
  (let [[qualifier var-name] (split-symbol (str/trim sym))
        ns-sym (some-> ns-str not-empty symbol)
        cljs-env (cljs/grab-cljs-env msg)
        candidates (if cljs-env
                     (resolve-missing-cljs cljs-env ns-sym qualifier var-name)
                     (resolve-missing-clj ns-sym qualifier var-name))
        named (filter #(= qualifier (:name %)) candidates)
        ;; Making a namespace's name the alias of another namespace would
        ;; change what it means.
        ns-name? (when qualifier
                   (let [q (symbol qualifier)]
                     (if cljs-env
                       (or (#{'cljs.core 'clojure.core} q)
                           (cljs-analysis/find-ns cljs-env q))
                       (find-ns q))))]
    ;; A fully qualified symbol leaves no choice.
    (->> (if (or ns-name? (seq named)) named candidates)
         distinct
         (sort-by :name))))

(defn- resolve-missing-reply [msg]
  {:candidates (resolve-missing msg)})

;;; Resolving aliases
;;
;; `cider/resolve-alias` lists the namespaces an alias could stand for, so that
;; typing `str/` can add the require it needs.  The project's own source files
;; decide: the namespaces they give that alias, the most used first.  Only when
;; they don't use the alias at all do the client's preferred namespaces and the
;; project namespaces named after it come in.

(def ^:private ns-forms-cache
  "Source file path to the file's last modified time and length, and what
  `read-file-aliases` got from it."
  (atom {}))

(defn- under-target?
  "Whether the directory `f` is in a `target` directory of the project."
  [^File f]
  (some #{"target"} (str/split (util.io/relativize-project-path f) #"[/\\]")))

(defn- project-source-dirs
  "The directories on the classpath with the project's own sources.  Resources
  and build output are left out, as copies of library sources end up there."
  []
  (for [^URL url (cp/classpath)
        :when (= "file" (.getProtocol url))
        :let [f (io/as-file url)]
        :when (and (.isDirectory f)
                   (util.io/file-in-project? f)
                   (not (re-find #"(^|-)resources$" (.getName f)))
                   (not (str/includes? (.getPath f) ".gitlibs"))
                   (not (under-target? f)))]
    f))

(defn- skipped-dir?
  "Whether the search for source files leaves out the directory `f`: a hidden
  one, build output, npm packages, or a symbolic link, which can loop."
  [^File f]
  (let [dir-name (.getName f)]
    (or (.startsWith dir-name ".")
        (contains? #{"target" "node_modules"} dir-name)
        (Files/isSymbolicLink (.toPath f)))))

(defn- source-files
  "The Clojure source files under `dir`."
  [^File dir]
  (filter (fn [^File f]
            (and (.isFile f) (re-find #"\.clj[cs]?$" (.getName f))))
          (tree-seq (fn [^File f]
                      (and (.isDirectory f)
                           (or (= dir f) (not (skipped-dir? f)))))
                    (fn [^File f] (seq (.listFiles f)))
                    dir)))

(defn- file-platforms [^File f]
  (let [file-name (.getName f)]
    (cond
      (.endsWith file-name ".cljc") [:clj :cljs]
      (.endsWith file-name ".cljs") [:cljs]
      :else [:clj])))

(defn- read-file-aliases
  "The ns name and the aliases of the source file `f`, for each platform it's
  for.  A file that doesn't read gets nothing."
  [^File f]
  (into {}
        (for [platform (file-platforms f)
              ;; Read for each platform, as a .cljc file can have a separate
              ;; ns form in a reader conditional for each.
              :let [form (ns-form/read-ns-form f platform)
                    ns-name (second form)]
              :when (symbol? ns-name)]
          [platform {:ns ns-name
                     :aliases (try (ns-form/aliases form)
                                   (catch Exception _ {}))}])))

(defn- project-aliases
  "The ns name and the aliases of each of the project's source files, for each
  platform the file is for.  Only the files that changed since the last call
  get read again."
  []
  ;; Two calls at once would both read every file that changed.
  (locking ns-forms-cache
    (let [cached @ns-forms-cache
          fresh (into {}
                      (for [dir (project-source-dirs)
                            ^File f (source-files dir)
                            :let [path (.getPath f)
                                  ;; The length catches a change within the
                                  ;; time resolution of the file system.
                                  stamp [(.lastModified f) (.length f)]
                                  [cached-stamp info] (get cached path)]]
                        [path [stamp (if (= stamp cached-stamp)
                                       info
                                       (read-file-aliases f))]]))]
      ;; Only the files there are now, so that deleted ones drop out.
      (reset! ns-forms-cache fresh)
      (map second (vals fresh)))))

(defn- has-source?
  "Whether the classpath has a file for the namespace `ns-str` with one of the
  `extensions`."
  [^String ns-str extensions]
  (let [path (-> ns-str (str/replace "-" "_") (str/replace "." "/"))]
    (some #(io/resource (str path %)) extensions)))

(defn- cljs-on-classpath? []
  (some? (io/resource "cljs/core.cljs")))

(defn- available?
  "Whether the namespace `ns` (a symbol, or a string for a JavaScript module)
  can be required on `platform`.  `cljs-env` is the ClojureScript compiler
  environment, when there's one."
  [ns platform cljs-env]
  (if (string? ns)
    (= :cljs platform)
    (let [ns-str (str ns)]
      (case platform
        :clj (or (find-ns ns)
                 (has-source? ns-str [".clj" ".cljc" "__init.class"]))
        :cljs (or (some-> cljs-env (cljs-analysis/find-ns ns))
                  (has-source? ns-str [".cljs" ".cljc"])
                  ;; ClojureScript takes a missing clojure.x for cljs.x.
                  (and (.startsWith ns-str "clojure.")
                       (has-source? (str "cljs" (subs ns-str (count "clojure"))) [".cljs" ".cljc"]))
                  ;; The Closure Library's JavaScript files aren't named after
                  ;; their namespaces.
                  (.startsWith ns-str "goog.")
                  ;; Without ClojureScript on the classpath there's no telling.
                  (not (cljs-on-classpath?)))))))

(defn- suggested-alias
  "The alias a namespace named `ns-sym` would usually get: the last segment of
  its name that isn't `core` or the like, e.g. `billing` for `acme.billing.core`.
  None for test namespaces."
  [ns-sym]
  (let [segments (remove #{"core" "api" "alpha"} (str/split (str ns-sym) #"\."))]
    (when-not (some #{"test"} segments)
      (last segments))))

(defn- has-alias?
  "Whether the namespace `ns-sym` already has `alias`, or `alias` names a
  namespace or a class rather than standing for a namespace."
  [cljs-env ns-sym alias]
  (if cljs-env
    (or (cljs-analysis/find-ns cljs-env alias)
        (when-let [ns-info (some->> ns-sym (cljs-analysis/find-ns cljs-env))]
          (some #(contains? (get ns-info %) alias)
                [:requires :require-macros :as-aliases :imports])))
    (or (find-ns alias)
        (let [the-ns (some-> ns-sym find-ns)
              ;; Every namespace has java.lang's classes.
              ^Namespace mappings (or the-ns (find-ns 'clojure.core))]
          (or (some-> the-ns clojure.core/ns-aliases (contains? alias))
              (class? #_(.getMapping mappings alias) ;; BB-PATCH
(get (ns-map mappings) alias)))))))

(defn- preferred-ns
  "The namespace an entry of the `:preferred` list names: a symbol, or a
  string for a JavaScript module, written quoted as in a libspec."
  [x]
  (let [s (str/trim (str x))]
    (if (and (> (count s) 1) (str/starts-with? s "\"") (str/ends-with? s "\""))
      (subs s 1 (dec (count s)))
      (symbol s))))

(defn- distinct-ns [candidates]
  (->> candidates
       (reduce (fn [[seen acc] {:keys [ns] :as c}]
                 (if (seen ns) [seen acc] [(conj seen ns) (conj acc c)]))
               [#{} []])
       second))

(defn resolve-alias
  "The namespaces the `:alias` of `msg` could stand for in its `:ns`, as maps
  with the `:ns` as written in a libspec and the `:source` of the candidate:
  `project`, with the `:count` of the source files that give the namespace this
  alias, `preferred` (from the `:preferred` namespaces) or `suggested`.
  `:platform` is `clj`, `cljs`, or `cljc`, where a candidate has to work for
  both, and defaults to the session's."
  [{alias-str :alias ns-str :ns platform :platform preferred :preferred :as msg}]
  (when (str/blank? alias-str)
    (throw (IllegalArgumentException. "No alias to resolve")))
  (let [alias (symbol (str/trim alias-str))
        current (some-> ns-str not-empty symbol)
        cljs-env (cljs/grab-cljs-env msg)
        platforms (case (or platform (if cljs-env "cljs" "clj"))
                    "cljs" [:cljs]
                    "cljc" [:clj :cljs]
                    [:clj])]
    (when-not (or (and (= 'js alias) (some #{:cljs} platforms))
                  (has-alias? cljs-env current alias))
      (let [files (project-aliases)
            ;; The current namespace's own file only tells what it has already.
            others (if current
                     (remove (fn [info] (some #(= current (get-in info [% :ns])) platforms))
                             files)
                     files)
            aliased-as (fn [info platform] (get-in info [platform :aliases alias]))
            used-on? (fn [ns platform] (some #(= ns (aliased-as % platform)) others))
            ;; What the project uses or defines on a platform is there for it.
            usable? (fn [ns]
                      (and (not= ns current)
                           (every? (fn [platform]
                                     (or (used-on? ns platform)
                                         (some #(= ns (get-in % [platform :ns])) files)
                                         (available? ns platform cljs-env)))
                                   platforms)))
            candidate (fn [ns source] {:ns (pr-str ns) :source source})
            used (->> others
                      (mapcat (fn [info] (distinct (keep #(aliased-as info %) platforms))))
                      frequencies
                      (filter (comp usable? key))
                      ;; For cljc, what the project uses on both platforms
                      ;; comes first.
                      (sort-by (fn [[ns n]]
                                 [(if (every? #(used-on? ns %) platforms) 0 1) (- n) (str ns)])))]
        (if (seq used)
          (for [[ns n] used]
            (assoc (candidate ns "project") :count n))
          (distinct-ns
           (concat
            (for [x (if (sequential? preferred) preferred [preferred])
                  :when (some? x)
                  :let [ns (preferred-ns x)]
                  :when (usable? ns)]
              (candidate ns "preferred"))
            (for [ns (sort (distinct (for [info files, platform platforms
                                           :let [ns (get-in info [platform :ns])]
                                           :when ns]
                                       ns)))
                  :when (and (= (str alias) (suggested-alias ns)) (usable? ns))]
              (candidate ns "suggested")))))))))

(defn- resolve-alias-reply [msg]
  {:candidates (resolve-alias msg)})

;;; Cleaning the ns form
;;
;; `cider/clean-ns` tells what the ns form requires, refers and imports that
;; the code after it doesn't use, by reading the code rather than loading
;; it. The client removes those parts itself, keeping the rest of the ns
;; form as it's written.

(defn clean-ns
  "What the ns form at the start of the `:code` of `msg` requires, refers and
  imports that the rest of it doesn't use, as `orchard.ns-form/unused` tells.
  `:line` is the line the code starts on, 1 by default, and `:platform` is
  `clj`, `cljs` or `cljc`."
  [{:keys [code line platform]}]
  (ns-form/unused (doto (LineNumberingPushbackReader. (StringReader. (str code)))
                    (.setLineNumber (int (or line 1))))
                  (#{:clj :cljs :cljc} (some-> platform keyword))))

(defn- clean-ns-reply [msg]
  (try
    (if-let [{:keys [libspecs imports]} (clean-ns msg)]
      {:libspecs (for [{:keys [ns clause as as-alias refer refer-macros] remove? :remove} libspecs]
                   (cond-> {:ns (pr-str ns), :clause (name clause)}
                     remove? (assoc :remove "true")
                     as (assoc :as (str as))
                     as-alias (assoc :as-alias (str as-alias))
                     refer (assoc :refer (map str refer))
                     refer-macros (assoc :refer-macros (map str refer-macros))))
       :imports (map str imports)}
      {:status :cider/clean-ns-error
       :error "No ns form to clean"})
    ;; The code doesn't read.
    (catch Exception e
      (let [line #_(:clojure.error/line (ex-data e)) ;; BB-PATCH sci's reader error carries :line
(let [d (ex-data e)] (or (:clojure.error/line d) (:line d)))]
        (cond-> {:status :cider/clean-ns-error
                 :error (ex-message (or (ex-cause e) e))}
          line (assoc :line line))))))

(defn handle-ns [handler msg]
  (with-safe-transport handler msg
    (merge
     (with-op-aliases {"cider/ns-list" ns-list-reply
                       "cider/ns-list-vars-by-name" ns-list-vars-by-name-reply
                       "cider/ns-vars" ns-vars-reply
                       "cider/ns-vars-with-meta" ns-vars-with-meta-reply
                       "cider/ns-path" ns-path-reply
                       "cider/ns-load-all" ns-load-all-reply
                       "cider/ns-aliases" ns-aliases-reply})
     ;; Newer, with no unqualified aliases, which would shadow
     ;; refactor-nrepl's `resolve-missing` and `clean-ns`.
     {"cider/resolve-missing" resolve-missing-reply
      "cider/resolve-alias" resolve-alias-reply
      "cider/clean-ns" clean-ns-reply})))
