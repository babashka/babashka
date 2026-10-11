(ns babashka.nrepl.impl.track-state
  "Sends CIDER the vars of each namespace after an eval, with the metadata
  CIDER uses for font-lock."
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [babashka.nrepl.impl.debug :as debug]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.session :as session]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]))

(def ^:private eval-ops
  #{"eval" "load-file" "cider/undef" "undef" "cider/undef-all" "undef-all"})

(def ^:private relevant-meta-keys
  [:deprecated :macro :test :indent :style/indent :cider/instrumented])

(def ^:private clojure-core (the-ns 'clojure.core))

(defn- file-kind [v]
  (let [f (:file (meta v))]
    (cond
      (or (nil? f) (= "NO_SOURCE_PATH" f)) nil
      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]+:" f) :library
      (fs/absolute? f) :project
      :else :library)))

(defn- project?
  "Returns true if a var of the-ns comes from a file by absolute path, or
  if no var comes from a jar, a bundled source or bb itself."
  [the-ns]
  (let [kinds (into #{} (keep file-kind) (vals (ns-interns the-ns)))]
    (or (contains? kinds :project) (not (contains? kinds :library)))))

(defn- key-str [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- var-state
  "Returns the metadata of v that CIDER reads.
  A var with :arglists and without :macro counts as a function."
  [v]
  ;; deref would read stdin through user/*input*
  (let [m (meta v)
        start (if (and (:arglists m) (not (:macro m)))
                {"fn" "true"}
                {})]
    (reduce (fn [acc k]
              (if-let [val (k m)]
                (assoc acc (key-str k) (pr-str val))
                acc))
            start relevant-meta-keys)))

(defn- vars-state [vars]
  (reduce-kv (fn [acc sym v]
               (if (and (var? v) (not (identical? clojure-core (:ns (meta v)))))
                 (assoc acc (str sym) (var-state v))
                 acc))
             {} vars))

(defn- ns-state
  "Returns the aliases and vars of the-ns.
  vars is ns-map for a project namespace, or ns-interns for a namespace a
  project namespace aliases."
  [the-ns vars]
  {"aliases" (reduce-kv (fn [acc alias aliased] (assoc acc (str alias) (str (ns-name aliased))))
                        {} (ns-aliases the-ns))
   "interns" (vars-state (vars the-ns))})

(def ^:private core-state
  (delay {"aliases" {}
          "interns" (reduce-kv (fn [acc sym v]
                                 (if (var? v) (assoc acc (str sym) (var-state v)) acc))
                               {} (ns-interns clojure-core))}))

(defn- project-state
  "Returns the state of every project namespace, of clojure.core and of
  every namespace those alias."
  []
  (let [state (reduce (fn [acc the-ns]
                        (if (and (not (identical? clojure-core the-ns)) (project? the-ns))
                          (assoc acc (str (ns-name the-ns)) (ns-state the-ns ns-map))
                          acc))
                      {"clojure.core" @core-state}
                      (all-ns))]
    (reduce-kv (fn [acc _ {:strs [aliases]}]
                 (reduce (fn [acc ns-str]
                           (if-let [the-ns (and (not (contains? acc ns-str)) (find-ns (symbol ns-str)))]
                             (assoc acc ns-str (ns-state the-ns ns-interns))
                             acc))
                         acc (vals aliases)))
               state state)))

(defn- state-response
  "Returns a state reply with the namespaces changed since the previous
  reply in the session of msg."
  [{:keys [session] :as msg}]
  (locking session
    (let [old (::project-state (meta session))
          new (project-state)
          changed (reduce-kv (fn [acc ns-str state]
                               (if (= state (get old ns-str)) acc (assoc acc ns-str state)))
                             {} new)]
      (alter-meta! session assoc ::project-state new)
      (response-for msg :status :state :repl-type "clj" :changed-namespaces changed))))

(defn- done? [{:keys [status]}]
  (if (coll? status)
    (boolean (some #{:done "done"} status))
    (contains? #{:done "done"} status)))

(defn- state-transport [{:keys [transport] :as msg}]
  (reify t/Transport
    (recv [_this] (t/recv transport))
    (recv [_this timeout] (t/recv transport timeout))
    (send [this resp]
      (when (done? resp)
        (try (t/send transport (state-response msg))
             (catch Exception _ nil)))
      (t/send transport resp)
      this)))

(defn wrap-track-state
  "Sends the changed namespaces before the done of each eval, and on
  `cider/get-state`."
  [h]
  (fn [{:keys [op] :as msg}]
    (cond
      (= "cider/get-state" op)
      (t/send (:transport msg) (assoc (state-response msg) :status #{:state :done}))
      (contains? eval-ops op)
      (h (assoc msg :transport (state-transport msg)))
      :else (h msg))))

(set-descriptor! #'wrap-track-state
                 {:requires #{#'session/session}
                  :expects (conj eval-ops #'debug/wrap-debug)
                  :handles {"cider/get-state"
                            {:doc "Returns the REPL type and the namespaces changed since the last reply."
                             :requires {}
                             :optional {}
                             :returns {"repl-type" "`clj`."
                                       "changed-namespaces" "A map of namespaces to `aliases` and `interns`."}}}})
