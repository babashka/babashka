(ns nrepl.middleware
  "Middleware descriptor infrastructure.

  Provides `set-descriptor!` for declaring middleware metadata
  (dependencies, handled ops, documentation) and
  `linearize-middleware-stack` for ordering middleware according
  to their `:requires` and `:expects` constraints. Also includes
  the built-in `wrap-describe` middleware."
  {:added "0.4"}
  (:refer-clojure :exclude [comparator])
  (:require
   [nrepl.misc :as misc]
   [nrepl.transport :as t :refer [safe-handle]]
   [nrepl.version :as version]))

;; Registering dynvars that are used to configure middleware. This lives here
;; and not in nrepl.middleware.session to avoid circular dependencies.

(def ^:private per-session-dynvars
  "Set of dynamic variables that always have to be established for a new session
  to their initial values."
  (atom #{}))

(defn- var-name
  [^clojure.lang.Var v]
  (str (.ns v) \/ (.sym v)))

(defn set-descriptor!
  "Sets the given [descriptor] map as the ::descriptor metadata on the
  provided [middleware-var], after assoc'ing in the var's fully-qualified name
  as the descriptor's \"implemented-by\" value. The value of `:session-dynvars`
  should be a set of dynamic variables that the middleware expects to be made
  rebindable for each new session."
  [middleware-var descriptor]
  (let [descriptor (assoc descriptor
                          :implemented-by (-> middleware-var var-name symbol))]
    (swap! per-session-dynvars into (:session-dynvars descriptor))
    (alter-meta! middleware-var assoc ::descriptor descriptor)))

(defn- safe-version
  [m]
  (into {} (filter (fn [[_ v]] (or (number? v) (string? v))) m)))

(defn- java-version
  []
  {:major misc/java-version
   :version-string (System/getProperty "java.version")})

(defn- describe-reply
  [{:keys [verbose?] :as msg} middleware]
  (let [descriptors (mapv (comp ::descriptor meta) middleware)
        ops (let [ops (apply merge (map :handles descriptors))]
              (if verbose?
                ops
                (zipmap (keys ops) (repeat {}))))
        aux (reduce
             (fn [aux {:keys [describe-fn]}]
               (cond-> aux
                 describe-fn (merge (describe-fn msg))))
             nil descriptors)]
    (merge
     {:ops ops
      :middleware (mapv str middleware)
      :versions {:nrepl (safe-version version/version)
                 :clojure (safe-version
                           (assoc *clojure-version* :version-string (clojure-version)))
                 :java (safe-version (java-version))}
      :status :done}
     (when aux {:aux aux}))))

(defn wrap-describe
  "Special middleware that accepts the list of all other middleware (except self)
  and handles `describe` op."
  [h middleware]
  (let [middleware+self (conj middleware #'wrap-describe)]
    (fn [msg]
      (safe-handle msg
        "describe" #(describe-reply % middleware+self)
        :else h))))

(set-descriptor! #'wrap-describe
                 {:handles {"describe"
                            {:doc "Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint."
                             :requires {}
                             :optional {"verbose?" "Include informational detail for each \"op\"eration in the return message."}
                             :returns {"ops" "Map of \"op\"erations supported by this nREPL endpoint"
                                       "middleware" "Current middleware stack, in inside-out order (earliest middleware is last)."
                                       "versions" "Map containing version maps (like *clojure-version*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include \"nrepl\" and \"clojure\"."
                                       "aux" "Map of auxiliary data contributed by all of the active nREPL middleware via :describe-fn functions in their descriptors."}}}})

(defn- comparator
  [{a-requires :requires a-expects :expects a-handles :handles}
   {b-requires :requires b-expects :expects b-handles :handles}]
  (let [intersect? (fn [set1 set2] (some (set set1) set2))]
    (cond (intersect? a-requires b-handles) -1
          (intersect? a-expects b-handles) 1
          (intersect? b-requires a-handles) 1
          (intersect? b-expects a-handles) -1
          :else 0)))

(defn- extended-descriptors
  "This function serves two purposes:
  - Scan through middlewares' dependencies and add those that are mentioned in
  `:requires` or `:expects`, but not included explicitly. This is done for
  compatibility, but will be removed in the future.
  - Return descriptor for each middleware."
  [middlewares]
  (let [mware-set (set middlewares)]
    (->> (for [m middlewares
               :let [{:keys [expects requires] :as desc} (::descriptor (meta m))]]
           (do (when (nil? desc)
                 (misc/log :warning "No nREPL middleware descriptor in metadata of" m
                           ", see nrepl.middleware/set-descriptor!"))
               (let [deps (concat expects requires)
                     missing-deps (for [dep deps
                                        :when (and (var? dep) (not (mware-set dep)))]
                                    (do (misc/log :warning (format "Middleware %s is required by %s but is not present in middleware list." dep m))
                                        dep))]
                 ;; TODO: stop adding missing deps in future versions.
                 (concat [m] missing-deps))))
         (apply concat)
         distinct
         (mapv (fn [m]
                 (let [desc (::descriptor (meta m))]
                   (-> desc
                       ;; Conj the middleware Var itself to `:handles` to support direct
                       ;; reference to middlewares dependencies in `:expects`/`:requires`.
                       (update :handles #(set (conj (keys %) m)))
                       (assoc :implemented-by m))))))))

(defn- topologically-sort
  "Topologically sorts the given middleware descriptors according to
  expects/requires rules with the added heuristic that any middlewares that have
  no dependencies will be sorted toward the end."
  [stack]
  (let [stack (vec stack)
        ;; using indexes into the above vector as the vertices in the
        ;; graph algorithm, will translate back into middlewares at
        ;; the end.
        vertices (range (count stack))
        edges (for [i1 vertices
                    i2 (range i1)
                    :let [x (comparator (stack i1) (stack i2))]
                    :when (not= 0 x)]
                (if (neg? x) [i1 i2] [i2 i1]))
        connected (into #{} cat edges)
        ;; the trivial vertices have no connections, and we pull them
        ;; out here so we can make sure they get put on the end
        trivial-vertices (remove connected vertices)]
    (loop [sorted-vertices []
           remaining-edges edges
           remaining-vertices (filter connected vertices)]
      (if (seq remaining-vertices)
        (let [non-initials (->> remaining-edges
                                (map second)
                                (set))
              next-vertex (->> remaining-vertices
                               (remove non-initials)
                               (first))]
          (if next-vertex
            (recur (conj sorted-vertices next-vertex)
                   (remove #((set %) next-vertex) remaining-edges)
                   (remove #{next-vertex} remaining-vertices))
            ;; Cycle detected! Have to actually assemble a cycle so we
            ;; can throw a useful error.
            (let [start (first remaining-vertices)
                  step (into {} remaining-edges)
                  cycle (->> (iterate step start)
                             (rest)
                             (take-while (complement #{start}))
                             (cons start))
                  data {:cycle (map stack cycle)}]
              (throw (ex-info
                      "Unable to satisfy nREPL middleware ordering requirements!"
                      data)))))
        (map stack (concat sorted-vertices trivial-vertices))))))

(defn linearize-middleware-stack
  "Given a list of middleware vars, sort them topologically, so that least
  dependent middleware come FIRST and ones dependent on come LAST. The returned
  order lends itself to being composed as-is into the final handler."
  [middleware-vars]
  (->> (extended-descriptors middleware-vars)
       topologically-sort
       (map :implemented-by)))
