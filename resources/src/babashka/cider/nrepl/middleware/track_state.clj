(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba, Oleksandr Yakushev"}
  (:require
   [babashka.fs :as fs] ;; BB-PATCH
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.middleware.util :as util]
   #_[cider.nrepl.middleware.util.cljs :as cljs] ;; BB-PATCH no ClojureScript environment in bb
   [cider.nrepl.middleware.util.meta :as um]
   [clojure.java.io :as io]
   [clojure.string :as str]
   #_[cider.nrepl.inlined.deps.toolsnamespace.v1v5v1.clojure.tools.namespace.find :as ns-find] ;; BB-PATCH no jar scan, see jar-namespaces
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   #_[orchard.cljs.analysis :as cljs-ana] ;; BB-PATCH
   [orchard.indent :as indent]
   #_[orchard.java.classpath :as cp] ;; BB-PATCH
   [orchard.misc :as misc])
  (:import (clojure.lang MultiFn Namespace Var) (java.io File) (java.util WeakHashMap) (java.util.jar JarFile) #_(nrepl.transport Transport))) ;; BB-PATCH a sci protocol is not a class

;;; Shared part

(defn- inferrable-indent?
  "Does metadata map `m` lack, need a `:style/indent` value, and is a suitable candidate for it?"
  [m]
  (and (:macro m)
       (:arglists m)
       (not (contains? m :style/indent))
       (not (contains? m :indent))
       (if-let [namespace-name (some-> (cond
                                         (instance? Namespace (:ns m)) ;; JVM clojure
                                         (-> m :ns ns-name)

                                         (symbol? (:ns m)) ;; possible edge cases
                                         (:ns m)

                                         ;; cljs analyzer info
                                         :else
                                         (some-> m :name namespace symbol))
                                       str)]
         ;; Official Clojure libraries are unlikely to ever include :style/indent metadata.
         ;; So our normal recommendation of adding it to any macro cannot apply here.
         ;; Inferring metadata for these would be problematic because:
         ;; * Their intended indents are already intentfully coded in clojure-mode.el
         ;; * The `orchard.indent` logic is not meant to operate on clojure.core stuff,
         ;;   because it compares a given macro against a clojure.core counterpart
         ;;   (which doesn't make sense for a macro which already belongs to clojure.core)
         (not (or (str/starts-with? namespace-name "clojure.")
                  (str/starts-with? namespace-name "cljs.")))
         true)))

;;; Clojure part

(def ^:private clojure-core (try (find-ns 'clojure.core)
                                 (catch Exception _e nil)))

;; A "real metadata cache" is a map {ns-symbol WHM{var-symbol meta}} of
;; actual (not filtered) var metadata. We keep this to know when to recompute
;; the filtered metadata for a var (if the real meta hasn't changed, no need to
;; recompute). WHM is used to avoid unnecessarily holding onto the metadata if
;; the Var has been removed somehow.
(def ^:dynamic *real-metadata-cache* nil)
(def ^:dynamic *old-project-state* nil)

(defn- get-metadata-if-changed?
  [^Var the-var, ^WeakHashMap real-metadata-ns-cache]
  (let [var-name (.sym the-var)
        cached-meta (some-> real-metadata-ns-cache (.get var-name))
        current-meta (meta the-var)]
    (when-not (identical? cached-meta current-meta)
      (some-> real-metadata-ns-cache (.put var-name current-meta))
      current-meta)))

;; Note that we aggressively cut down unnecessary keys in this mw as it triggers
;; on EACH evaluation, and the middleware retains (caches) this data for ALL
;; loaded project namespaces both on cider-nrepl and CIDER side. This list is
;; smaller than the one used by `ns-*` middleware.

(def ^:private relevant-meta-keys
  [:deprecated :macro :test :indent :style/indent :cider/instrumented
   :orchard.trace/traced :orchard.profile/profiled])

(def ^:private input-var (resolve 'user/*input*)) ;; BB-PATCH

(defn- compute-var-meta
  "Return only metadata for a var that is relevant to track-state middleware. Add
  `:fn true` for functions and multimethods. Infer `:style/indent` if missing.
  This function accepts two caches - 'real' metadata WHM cache and the previous
  computed ns-state. If the real metadata on a var hasn't changed, take the
  computed metadata from the cache."
  [^Var the-var, real-metadata-ns-cache old-ns-state]
  (let [mta (get-metadata-if-changed? the-var real-metadata-ns-cache)
        cached-computed-meta (when (nil? mta)
                               (get old-ns-state (.sym the-var)))]
    (or cached-computed-meta
        (let [mta (cond-> (or mta (meta the-var))
                    (inferrable-indent? mta) indent/infer-style-indent)
              ;; Most vars in the namespace are functions with no other relevant
              ;; metadata, so having {:fn "true"} as shared literal improves
              ;; both the computation speed and the occupied memory.
              obj #_(var-get the-var) ;; BB-PATCH deref of user/*input* reads stdin
(when-not (identical? input-var the-var) (var-get the-var))
              start (if (and (or (fn? obj) (instance? MultiFn obj))
                             (not (:macro mta)))
                      {:fn "true"} {})]
          (reduce (fn [result k]
                    (let [val (k mta)]
                      (cond-> result val (assoc k (pr-str val)))))
                  start relevant-meta-keys)))))

(declare jar-namespaces) ;; BB-PATCH

(defn- compute-var-metas-for-namespace
  "Compute relevant metadata for all vars in the namespace."
  [the-ns]
  (let [ns-sym (ns-name the-ns)
        old-project-ns-map (:interns (get *old-project-state* ns-sym))
        real-metadata-whm (if *real-metadata-cache*
                            (or (@*real-metadata-cache* ns-sym)
                                ((swap! *real-metadata-cache* assoc ns-sym
                                        (WeakHashMap.)) ns-sym))
                            (WeakHashMap.))]
    ;; WHM is not thread-safe, so synchronize the access to it to avoid infinite
    ;; loops like https://github.com/clojure-emacs/cider-nrepl/issues/936.
    (locking real-metadata-whm
      (reduce-kv (fn [acc sym the-var]
                   (if (and (var? the-var)
                            (not (identical? (.ns ^Var the-var)
                                             clojure-core)))
                     (let [old-meta (get old-project-ns-map sym)
                           new-meta (compute-var-meta the-var real-metadata-whm
                                                      old-project-ns-map)]
                       (if (identical? old-meta new-meta)
                         acc
                         (assoc acc sym new-meta)))
                     acc))
                 old-project-ns-map
                 #_(ns-map the-ns) ;; BB-PATCH a library namespace's refers cost time in sci and CIDER ignores them
((if (jar-namespaces ns-sym) ns-interns ns-map) the-ns)))))

(def clojure-core-map
  (when clojure-core
    {:aliases {}
     :interns (into {}
                    (keep (fn [[k v]]
                            (when (var? v)
                              [k (compute-var-meta v nil {})])))
                    (ns-map clojure-core))}))

;;; Clojurescript part

;; NB: must be bound for ClojureScript-related parts of the middleware to work!
(def ^:dynamic *cljs* nil)
(def ^:dynamic *all-cljs-namespaces* nil)

(defn- cljs-meta-with-fn
  "Like (:meta m) but adds {:fn true} if (:fn-var m) is true, (:tag m) is
  'function, or if m does not have a :tag key."
  [m]
  (cond-> (:meta m)
    (or (:fn-var m)
        ('#{function cljs.core/MultiFn} (:tag m))
        (not (contains? m :tag)))
    (assoc :fn true)))

(defn- remove-redundant-quote
  "Fixes double-quoted arglists coming from the ClojureScript analyzer."
  [{:keys [arglists] :as m}]
  (if (and (sequential? arglists)
           (= (first arglists) 'quote))
    (assoc m :arglists (second arglists))
    m))

(defn- uses-metadata
  "Creates a var->metadata map for all the `:uses` of a given cljs ns. It
  accomplishes so by querying `*all-cljs-namespaces*`"
  [uses]
  (into {}
        (map (fn [[var-name var-ns]]
               (let [defs (some->> *all-cljs-namespaces*
                                   (filter (fn [x]
                                             (and (map? x)
                                                  (= (:name x)
                                                     var-ns))))
                                   first
                                   :defs)]
                 (if-let [var-meta (some-> defs (get var-name) (get :meta))]
                   [var-name (remove-redundant-quote var-meta)]
                   [var-name {:arglists '([])}]))))
        uses))

(defn- use-macros-metadata
  "Creates a var->metadata map for all the `:use-macros` of a given cljs ns.

  It accomplishes so by querying the JVM Clojure environment."
  [use-macros]
  (into {}
        (map (fn [[var-name var-ns]]
               (if-let [var-ref (resolve (symbol (name var-ns)
                                                 (name var-name)))]
                 [var-name (meta var-ref)]
                 [var-name {:arglists '([]) :macro true}])))
        use-macros))

;;; Common part again

(defn ns-state [object]
  (cond
    ;; Clojure Namespaces
    (instance? Namespace object)
    {:aliases (misc/update-vals ns-name (ns-aliases object))
     :interns (compute-var-metas-for-namespace object)}

    ;; ClojureScript Namespaces
    (associative? object)
    (let [{:keys [use-macros uses macros require-macros requires defs]} object
          post-process (fn [result]
                         (misc/update-vals (fn [x]
                                             (cond-> x
                                               (inferrable-indent? x) indent/infer-style-indent
                                               ;; For some reason, cljs (or piggieback) adds a :test key to the
                                               ;; var metadata stored in the namespace.
                                               true (dissoc :test)
                                               true um/relevant-meta))
                                           result))]
      {:aliases (merge require-macros requires)
       :interns (merge (post-process (misc/update-vals cljs-meta-with-fn defs))
                       (post-process (uses-metadata uses))
                       (post-process (use-macros-metadata use-macros))
                       (post-process macros))})

    ;; Unresolved yet: resolve depending on the environment and recur.
    (symbol? object)
    (ns-state #_(if *cljs* ;; BB-PATCH
                (cljs-ana/find-ns *cljs* object)
                (find-ns object))
(find-ns object))

    :else {}))

#_(def ^:private jar-namespaces* ;; BB-PATCH bb's bundled namespaces are in no jar
  (future
    (into #{}
          (comp (filter misc/jar-file?)
                (keep (fn [url]
                        (let [^File file (io/as-file url)]
                          (when (.exists file)
                            (JarFile. file)))))
                (mapcat ns-find/find-namespaces-in-jarfile))
          (cp/classpath))))

#_(defn jar-namespaces [x] ;; BB-PATCH
  (contains? @jar-namespaces* x))

(defn- file-kind [v]
  (let [f (:file (meta v))]
    (cond
      (or (nil? f) (= "NO_SOURCE_PATH" f)) nil
      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]+:" f) :library
      (fs/absolute? f) :project
      :else :library)))

(defn jar-namespaces
  "Returns true if no var of namespace x comes from a file by absolute path,
  and some var comes from a jar, a bundled source or bb itself."
  [x]
  (let [kinds (into #{} (keep file-kind) (vals (ns-interns x)))]
    (and (not (contains? kinds :project)) (contains? kinds :library))))

(defn- initial-project-state [all-namespaces]
  (let [cljs *cljs*]
    (persistent!
     (reduce (fn [acc ns]
               (let [name (if cljs (:name ns) (ns-name ns))]
                 (if (jar-namespaces name)
                   acc ;; Remove all jar namespaces.
                   (assoc! acc name (ns-state ns)))))
             (transient {})
             all-namespaces))))

(defn- add-core-namespace-vars [project-state]
  (if *cljs*
    (assoc project-state 'cljs.core (ns-state 'cljs.core))
    (assoc project-state 'clojure.core clojure-core-map)))

(defn- merge-used-aliases
  "Return project state merged with all of its direct dependencies."
  [project-state]
  (reduce-kv (fn [acc _ {:keys [aliases]}]
               (reduce-kv (fn [acc _ ns-sym]
                            (if (contains? acc ns-sym)
                              acc
                              (assoc acc ns-sym (ns-state ns-sym))))
                          acc aliases))
             project-state project-state))

(defn calculate-changed-project-state
  "Return a map of namespaces that changed between new-project-state and
  old-project-state. New and old state maps from namespace symbols to namespace
  state, which is the same format of map returned by this function."
  [new-project-state old-project-state]
  (reduce-kv (fn [acc ns-sym ns-state]
               (if-not (= ns-state (get old-project-state ns-sym))
                 (assoc acc ns-sym ns-state)
                 acc))
             {} new-project-state))

;;; State management

(defn calculate-changed-project-state-response
  "Calculate changes in project state since we last notified the client. Response
  is a map:
  - `:repl-type` - either `:clj` or `:cljs`
  - `:changed-namespaces` - a map of namespaces that have changed

  The previous value of project-state is taken from the session metadata. Once
  the new value is computed, it has to be written into the session metadata.
  Also take 'real metadata cache' from the session metadata (it is mutable)."
  [{:keys [session] :as msg}]
  (let [old-project-state (::project-state (meta session))
        real-metadata-cache (or (::metadata-cache (meta session))
                                (::metadata-cache (alter-meta! session assoc ::metadata-cache (atom {}))))
        cljs #_(cljs/grab-cljs-env msg) nil ;; BB-PATCH
        all-namespaces #_(if cljs ;; BB-PATCH
                         (vals (cljs-ana/all-ns cljs))
                         (all-ns))
(all-ns)]
    (binding [*cljs* cljs
              *all-cljs-namespaces* (when cljs all-namespaces)
              *old-project-state* old-project-state
              *real-metadata-cache* real-metadata-cache]
      (let [project-state (-> (initial-project-state all-namespaces)
                              add-core-namespace-vars
                              merge-used-aliases)]
        (alter-meta! session assoc ::project-state project-state)
        (let [delta (calculate-changed-project-state project-state old-project-state)]
          (response-for msg
                        :status :state
                        :repl-type (if cljs :cljs :clj)
                        :changed-namespaces (util/transform-value delta)))))))

;;; Middleware
(defn make-transport
  "Return a Transport that defers to `transport` and possibly notifies
  about the state."
  #_[{:keys [^Transport transport session] :as msg}] ;; BB-PATCH
[{:keys [transport session] :as msg}]
  (reify #_Transport transport/Transport ;; BB-PATCH
    (recv [_this]
      #_(.recv transport) ;; BB-PATCH
(transport/recv transport))

    (recv [_this timeout]
      #_(.recv transport timeout) ;; BB-PATCH
(transport/recv transport timeout))

    #_(send [_this {:keys [status] :as response}] ;; BB-PATCH a plain thread, a future fails after shutdown-agents
      (.send transport response)
      (when (contains? status :done)
        (future
          (transport/send transport (calculate-changed-project-state-response msg)))))
(send [this {:keys [status] :as response}]
  (transport/send transport response)
  (when (some #{:done "done"} (if (coll? status) status [status]))
    (doto (Thread. (fn []
                     (transport/send transport (try (calculate-changed-project-state-response msg)
                                                    (catch Exception _ (response-for msg :status :state :repl-type "clj" :changed-namespaces {}))))))
      (.setName "nREPL-track-state")
      (.setDaemon true)
      (.start)))
  this)))

(defn handle-tracker [handler {:keys [op session] :as msg}]
  (cond
    (= "cider/get-state" op) (calculate-changed-project-state-response msg)

    (mw/ops-that-can-eval op)
    (handler (assoc msg :transport (make-transport msg)))

    :else
    (handler msg)))
