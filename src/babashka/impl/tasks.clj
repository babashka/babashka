(ns babashka.impl.tasks
  (:require
   [babashka.cli]
   [babashka.deps :as deps]
   [babashka.impl.cli :as cli]
   [babashka.impl.common :refer [bb-edn ctx debug]]
   [babashka.impl.process :as pp]
   [babashka.process :as p]
   [clojure.core.async :refer [<!!]]
   [clojure.string :as str]
   [rewrite-clj.node :as node]
   [rewrite-clj.parser :as parser]
   [rewrite-clj.zip :as zip]
   [sci.core :as sci])
  (:import
   [clojure.core.async.impl.channels ManyToManyChannel]))

(defn -chan? [x]
  (instance? ManyToManyChannel x))

(defn cli-node
  "The babashka.cli dispatch node for a task, or nil when the task is a plain
  one. Naming a handler (`:exec-fn`) or a command tree (`:cmd`) is what opts a
  task in. Those keys stay on the task, everything else babashka.cli
  takes lives under `:cli`, so reading a task map tells you which keys are bb's
  and which are the parser's. Inside `:cmd` there is no such split: those are
  babashka.cli nodes already."
  [task-map]
  (when (and (map? task-map)
             (or (:exec-fn task-map) (:cmd task-map)))
    (select-keys task-map [:exec-fn :cmd :doc :cli])))

(defn join-docs
  "A task `:doc` may be a vector of lines, convenient in edn. Joins it into
  the string every consumer expects."
  [edn]
  (if-let [tasks (:tasks edn)]
    (assoc edn :tasks
           (reduce-kv (fn [acc k v]
                        (if (and (symbol? k) (map? v) (vector? (:doc v)))
                          (assoc acc k (update v :doc #(str/join "\n" %)))
                          acc))
                      tasks tasks))
    edn))

(def sci-ns (sci/create-ns 'babashka.tasks nil))
(def default-log-level :error)
(def log-level (sci/new-dynamic-var '*-log-level* default-log-level {:ns sci-ns}))
;; (def task-name (sci/new-dynamic-var '*-task-name* nil {:ns sci-ns}))
(def task (sci/new-dynamic-var '*task* nil {:ns sci-ns}))
(def current-task (sci/new-var 'current-task (fn [] @task) {:ns sci-ns}))
(def state (sci/new-var 'current-state (atom {}) {:ns sci-ns}))

(defn log-info [& strs]
  (let [log-level @log-level]
    (when
        ;; do not log when level is :error
     (identical? :info log-level)
      (binding [*out* *err*]
        (println (format "[bb %s]" (:name @task)) (str/join " " strs))))))

(defn- handle-non-zero [proc opts]
  (when proc
    (when-let [proc (deref proc)]
      (let [exit-code (:exit proc)
            zero-exit? (zero? exit-code)
            continue (:continue opts)
            continue? (if continue
                        (or (true? continue)
                            (continue proc))
                        zero-exit?)
            info {:proc proc
                  :task task
                  :babashka/exit exit-code}]
        (if continue? proc
            (if-let [err-fn (:error-fn opts)]
              (err-fn info)
              (throw (ex-info (str "Error while executing task: " (:name @task))
                              info))))))))

(def default-opts
  {:in :inherit
   :out :inherit
   :err :inherit
   :shutdown p/destroy-tree})

(defn shell [& args]
  (let [{:keys [prev cmd opts]} (p/parse-args args)
        local-log-level (:log-level opts)
        opts (merge default-opts opts)]
    (sci/binding [log-level (or local-log-level @log-level)]
      (apply log-info args)
      (handle-non-zero (pp/process* {:opts opts :cmd cmd :prev prev}) opts))))

(defn clojure [& args]
  (let [[cmd & args] args
        [opts cmd args]
        (if (map? cmd)
          [cmd (first args) (rest args)]
          [nil cmd args])
        cmd (cond-> args
              cmd (->> (cons cmd)))
        local-log-level (:log-level opts)]
    (sci/binding [log-level (or local-log-level @log-level)]
      (apply log-info (cons "clojure" cmd))
      (handle-non-zero (apply deps/clojure (merge default-opts opts) cmd) opts))))

(defn -wait [res]
  (when res
    (if (-chan? res)
      (let [[_task-name res] (<!! res)]
        (if (instance? Throwable res)
          (throw (ex-info (ex-message res)
                          {:babashka/exit 1
                           :data (ex-data res)}))
          res))
      res)))

(defn depends-map [tasks target-name]
  (let [deps (seq (:depends (get tasks target-name)))
        m [target-name deps]]
    (into {} (cons m (map #(depends-map tasks %) deps)))))

(defmacro -err-thread [name & body]
  `(clojure.core.async/thread
     (try [~name ~@body]
          (catch Throwable e#
            [~name (ex-info (str "Error in task: " ~name
                                 "\n" (ex-message e#))
                            (or (ex-data e#) {}))]))))

(defn wrap-body [task-map prog parallel?]
  (format "(binding [
  babashka.tasks/*task* '%s]
  %s)"
          (pr-str task-map)
          (if parallel?
            (format "(babashka.tasks/-err-thread \"%s\" %s)" (:name task-map) prog)
            prog)))

(defn wrap-def [task-map prog parallel? last?]
  (let [task-name (:name task-map)]
    (format "(def %s %s) %s"
            task-name (wrap-body task-map prog parallel?)
            (if (and parallel? last?)
              (format "(babashka.tasks/-wait %s)" task-name)
              task-name))))

(def o (Object.))

#_:clj-kondo/ignore
(defn- log
  "Used internally for debugging"
  [& strs]
  (locking o
    (apply prn strs)))

(defn wait-tasks [deps]
  (if deps
    (format
     (pr-str
      '(let [chans (filter babashka.tasks/-chan? %s)]
         (loop [cs chans]
           (when (seq cs)
             (let [[v* p] (clojure.core.async/alts!! cs)
                   [task-name v] v*
                   cs (filterv #(not= p %) cs)
                   _ (when v* (intern *ns* (symbol task-name) v))]
               (when (instance? Throwable v)
                 (throw (ex-info (ex-message v)
                                 {:babashka/exit 1
                                  :data (ex-data v)})))
               (recur cs))))
         ;; since resolving channels into values may happen in parallel and some
         ;; channels may have been resolved on other threads, we should wait
         ;; until all deps have been interned as values rather than chans
         ;; see issue 1190
         (loop [deps '%s]
           (when (some (fn [task-name]
                         (babashka.tasks/-chan? (deref (resolve (symbol task-name))))) deps)
             (recur deps))))) deps deps)
    ""))

(defn wrap-enter-leave [task-name prog enter leave]
  (str (pr-str enter) "\n"
       (if leave
         (format "
(let [%s %s]
  (binding [babashka.tasks/*task*
            (assoc babashka.tasks/*task* :result %s)]
    %s)
  %s)"
                 task-name prog task-name (pr-str leave) task-name)
         prog)))

(defn wrap-depends [prog depends parallel?]
  (if parallel?
    (format "(do %s)" (str (str "\n" (wait-tasks depends))
                           "\n" prog))
    prog))

(defn- fold-fn-meta
  "Merges a handler var's `:org.babashka/cli` metadata into its `node`, like
  `bb -x`: namespace metadata first, then the var's own, then its docstring,
  with explicit node keys winning over all of it. A `:cmd` tree on the fn is
  dropped, command trees belong in bb.edn. `var-meta` is nil when the node
  holds a fn object rather than a symbol, which leaves the node as it is."
  [var-meta node]
  (if var-meta
    (babashka.cli/merge-opts
     (:org.babashka/cli (meta (:ns var-meta)))
     (dissoc (:org.babashka/cli var-meta) :cmd)
     (when-let [d (:doc var-meta)] {:doc d})
     node)
    node))

(defn- resolve-or-throw
  "Resolves `sym` with `resolve-fn`, reporting `what` when it names a var that
  is not there. Covers both failures: a missing var resolves to nil, a missing
  namespace throws. The original failure is kept as the cause and its message
  is appended, since a bb.edn typo usually shows up as the namespace not being
  on the classpath."
  [resolve-fn sym what]
  (let [v (try (resolve-fn sym)
               (catch Exception e
                 (throw (ex-info (str what ": " (ex-message e))
                                 {:babashka/exit 1} e))))]
    (or v (throw (ex-info what {:babashka/exit 1})))))

(defn -resolve-cli-opts
  "A `:cli` entry resolved to a map: a map as-is, or a symbol naming a def of
  one, resolved via `resolve-fn` (the script's `requiring-resolve`). The symbol
  form is what holds options that include functions, such as an `:error-fn`,
  which bb.edn cannot express. nil when there is no entry. `what` names the
  entry in error messages. The same rule applies to the runner-level
  `:tasks {:cli ...}` and to a task's own `:cli`."
  [resolve-fn v what]
  (cond
    (nil? v) nil
    (map? v) v
    (symbol? v) (let [m @(resolve-or-throw resolve-fn v
                                           (str what " " v " cannot be resolved"))]
                  (when-not (map? m)
                    (throw (ex-info (str what " " v " is not a map")
                                    {:babashka/exit 1})))
                  m)
    :else (throw (ex-info (str what " must be a map or a symbol naming a def, got: " (pr-str v))
                          {:babashka/exit 1}))))

(defn -task-node
  "The dispatch node for a task: the keys bb reads (`:exec-fn`, `:cmd`, `:doc`)
  over the parser options its `:cli` resolves to. Both the invocation
  and the completion path go through here, so a task is described the same way
  whichever asks."
  [resolve-fn task-name node]
  (merge (-resolve-cli-opts resolve-fn (:cli node) (str "Task " task-name ": :cli"))
         (dissoc node :cli)))

(defn- map-cmd
  "Applies `f` to every command in a `:cmd`, keeping its shape. A vector of
  `[name command]` pairs is how babashka.cli takes an ordered command list, so
  rebuilding it as a map would throw that order away."
  [cmd f]
  (into (empty cmd) (map (fn [[name node]] [name (f node)])) cmd))

(defn -resolve-cli-specs
  "Walk a `:cli` tree, folding each node fn's metadata into its node with
  fold-fn-meta, for both `:fn` and `:exec-fn`. `resolve-fn` is the script's
  `requiring-resolve`. Used where the tree is inspected but the fns are not
  called (`--help` and shell completion), so a node's spec and doc show up even
  though they live on the fn. Unlike -cli-dispatch this does not insist that a
  symbol resolves: a stale name should not stop the rest from being described."
  [resolve-fn node]
  (let [merge-fn-meta (fn [node k]
                        (let [fv (k node)]
                          (fold-fn-meta (when (symbol? fv) (meta (resolve-fn fv)))
                                        node)))
        node (-> node (merge-fn-meta :fn) (merge-fn-meta :exec-fn))]
    (cond-> node
      (:cmd node) (update :cmd map-cmd #(-resolve-cli-specs resolve-fn %)))))

(defn -dep-node
  "A `:depends` task's node, ready to read a `:spec` off: its own `:cli` folded
  in, then its handler's metadata, the same two steps a target goes through."
  [resolve-fn task-name node]
  (-resolve-cli-specs resolve-fn (-task-node resolve-fn task-name node)))

(def ^:dynamic *cli-target?*
  "True while assembling for a target that dispatches, which is what binds
  `dep-opts-sym`. A plain target has no parse, so its CLI dependencies keep
  contributing nothing rather than referring to a symbol that is not there."
  false)

(def dep-opts-sym
  "Name the assembled `:depends` program binds the parsed options to, so a CLI
  dep can be handed the ones it declared."
  "__babashka-dep-opts")

(defn -run-cli-dep
  "Calls the handler of a CLI task named in `:depends`, with the options it
  declared. Emitted in the dep's own place in the assembled `:depends` program,
  so it keeps its position in the graph and its `:depends` still run first."
  [node task-name opts resolve-fn]
  (let [node (-dep-node resolve-fn task-name node)]
    (when-let [f (:exec-fn node)]
      ((if (symbol? f)
         (resolve-or-throw resolve-fn f
                           (str "Task " task-name ": cannot resolve :exec-fn " f))
         f)
       (select-keys opts (keys (:spec node)))))))

(defn -cli-dispatch
  "Runs babashka.cli/dispatch over a task's node. A `:fn` / `:exec-fn` symbol is
  resolved with `resolve-fn` (the script's `requiring-resolve`) and the var's
  metadata folded into its node, so specs and help live with the fn.

  `fns` holds what must not run until the parser picks a command: `:body-fn`
  (the task body), `:deps-fn` (the assembled `:depends` bodies) and `:hook-fn`
  (`:enter` / `:leave` around a call), each nil when absent. ADR 0001, decision
  10, covers what stays out of `:deps-fn` and why.

  `defaults` is the runner-level `:tasks {:cli ...}` entry, passed in rather
  than read here so that this and completion take the same route to it.

  `dep-nodes` are the `[name node]` pairs of the CLI `:depends` tasks. Their
  specs merge under this task's own, so one parse covers everything the
  invocation can consume. The handlers themselves are called from the assembled
  `:depends` program, in their own place in the graph. A dep never parses, so
  its `:restrict` does not apply here."
  [cli-opts task-name fns defaults dep-nodes resolve-fn args]
  (let [;; the task's own `:cli` also provides dispatch opts, for options that
        ;; only exist there, such as an `:error-fn`
        task-cli (-resolve-cli-opts resolve-fn (:cli cli-opts)
                                    (str "Task " task-name ": :cli"))
        cli-opts (-task-node resolve-fn task-name cli-opts)
        {:keys [body-fn deps-fn hook-fn]} fns
        dep-spec (reduce (fn [acc [nm node]]
                           (merge acc (:spec (-dep-node resolve-fn nm node))))
                         {} dep-nodes)
        with-deps (fn [f] (fn [m] (when deps-fn (deps-fn m)) (f m)))
        with-hooks (fn [f] (if hook-fn (fn [m] (hook-fn (fn [] (f m)))) f))
        ;; resolve a :fn / :exec-fn symbol, fold in the fn's metadata and gate
        ;; :depends and :enter/:leave on the fn being called
        wrap-key (fn [node k]
                   (if-let [fv (k node)]
                     (let [the-var (if (symbol? fv)
                                     (resolve-or-throw resolve-fn fv
                                                       (str "Task " task-name ": cannot resolve " k " " fv))
                                     fv)]
                       (-> (fold-fn-meta (when (symbol? fv) (meta the-var)) node)
                           (assoc k (with-deps (with-hooks the-var)))))
                     node))
        wrap (fn wrap [node]
               (let [node (-> node (wrap-key :fn) (wrap-key :exec-fn))]
                 (cond-> node
                   (:cmd node) (update :cmd map-cmd wrap))))
        tree (wrap cli-opts)
        tree (if body-fn (assoc tree :fn (with-deps body-fn)) tree)
        ;; a `:cli` entry in the :tasks map (like :requires/:init) provides
        ;; dispatch defaults for every CLI task, merged into the dispatch
        ;; opts; node keys win. `:prog` stays bb's, so help always names the
        ;; task it belongs to.
        defaults (-resolve-cli-opts resolve-fn defaults ":tasks :cli")]
    (babashka.cli/dispatch tree args (merge {:help true}
                                            defaults
                                            task-cli
                                            (babashka.cli/merge-opts
                                             (when (seq dep-spec) {:spec dep-spec})
                                             (select-keys defaults [:spec])
                                             (select-keys task-cli [:spec]))
                                            {:prog (str "bb " task-name)}))))

(defn wrap-cli
  "Emits the -cli-dispatch call for a CLI task, one naming an `:exec-fn` or a
  `:cmd` tree. `prog` is the assembled `:task` body, which becomes the root
  handler and is called with no arguments: parsed options are what `:exec-fn`
  is for. `dep-forms` and the task's `:enter` / `:leave` go over as thunks.
  `dep-nodes` are the `[name node]` pairs of the CLI `:depends` tasks, in
  dependency order."
  ([task-map prog dep-forms] (wrap-cli task-map prog dep-forms nil))
  ([task-map prog dep-forms dep-nodes]
   (if-let [cli-opts (cli-node task-map)]
     (let [{:keys [enter leave name]} task-map]
       (format "(babashka.tasks/-cli-dispatch '%s \"%s\" {:body-fn %s :deps-fn %s :hook-fn %s} '%s '%s requiring-resolve *command-line-args*)"
               (pr-str cli-opts)
               name
               (if (:task task-map)
                 (format "(fn [_] %s)" prog)
                 "nil")
               (if dep-forms
                 (format "(fn [%s] %s)" dep-opts-sym dep-forms)
                 "nil")
               (if (or enter leave)
                 (format "(fn [thunk] %s)" (wrap-enter-leave name "(thunk)" enter leave))
                 "nil")
               ;; the same value completion-program embeds, from the same place
               (pr-str (:cli (:tasks @bb-edn)))
               (pr-str (vec dep-nodes))))
     prog)))

(defn assemble-task-1
  "Assembles task, does not process :depends. `dep-forms` is only threaded for a
  `:cli` target (see wrap-cli): assembled `:depends` to run inside the body fn."
  ([task-map task parallel?]
   (assemble-task-1 task-map task parallel? nil))
  ([task-map task parallel? last?]
   (assemble-task-1 task-map task parallel? last? nil))
  ([task-map task parallel? last? dep-forms]
   (assemble-task-1 task-map task parallel? last? dep-forms nil))
  ([task-map task parallel? last? dep-forms dep-nodes]
   (let [[task depends task-map]
         (if (map? task)
           [(:task task)
            (:depends task)
            (merge task-map task)]
           [task nil (assoc task-map :task task)])
         enter (:enter task-map)
         leave (:leave task-map)
         task-name (:name task-map)
         private? (or (:private task)
                      (str/starts-with? task-name "-"))
         task-map (if private?
                    (assoc task-map :private private?)
                    task-map)
         ;; a qualified symbol calls that fn with the raw args, anything else is
         ;; a body form. Both go through the same pipeline, and a `:cli` task
         ;; keeps its dispatch either way: the symbol call is its default action
         qualified? (qualified-symbol? task)
         dep-cli-node (when (and (not last?) *cli-target?*)
                        (cli-node task-map))
         prog (if qualified?
                (format "(apply %s *command-line-args*)" task)
                (pr-str task))
         prog (if dep-cli-node
                (format "(do %s (babashka.tasks/-run-cli-dep '%s \"%s\" %s requiring-resolve))"
                        prog (pr-str dep-cli-node) task-name dep-opts-sym)
                prog)
         prog (wrap-enter-leave task-name prog enter leave)
         cli-target? (and last? (cli-node task-map))
         prog (if last? (wrap-cli task-map prog dep-forms dep-nodes) prog)
         prog (if (and cli-target? dep-forms)
                prog
                (wrap-depends prog depends parallel?))
         prog (wrap-def task-map prog parallel? last?)]
     (if qualified?
       (format "
(when-not (resolve '%s) (require (quote %s)))
%s"
               task
               (namespace task)
               prog)
       prog))))

(def rand-ns (delay (symbol (str "user-" (java.util.UUID/randomUUID)))))

(defn add-deps-form
  "`(babashka.deps/add-deps ...)` for `extra-paths` / `extra-deps`, or \"\"."
  [extra-paths extra-deps]
  (let [deps (cond-> {}
               (seq extra-deps) (assoc :deps extra-deps)
               (seq extra-paths) (assoc :paths extra-paths))]
    (if (seq deps)
      (format "(babashka.deps/add-deps '%s)" (pr-str deps))
      "")))

(defn requires-form
  "`(require ...)` for `requires`, or \"\"."
  [requires]
  (if (seq requires)
    (format "(require %s)"
            (str/join "\n" (map (fn [req] (str "'" req)) requires)))
    ""))

(defn format-task [init extra-paths extra-deps global-requires requires prog]
  (format "
%s ;; deps

(ns %s
;; global requires
%s)

(require '[babashka.tasks #_#_:refer [log]])
(when-not (resolve 'clojure)
  ;; we don't use refer so users can override this
  (intern *ns* 'clojure babashka.tasks/clojure))

(when-not (resolve 'shell)
  (intern *ns* 'shell babashka.tasks/shell))

(when-not (resolve 'current-task)
  (intern *ns* 'current-task babashka.tasks/current-task))

(when-not (resolve 'run)
  (intern *ns* 'run babashka.tasks/run))

(when-not (resolve 'exec)
  (intern *ns* 'exec @(var babashka.tasks/exec)))

;; init, name should not clash with existing tasks!
(defmacro __babashka$tasks$impl$init []
  (when-not (resolve '%s/__babashka$tasks$impl$init?)
   (intern '%s '__babashka$tasks$impl$init? true)
   '%s))

(__babashka$tasks$impl$init)
;; task requires
%s
;; task
%s
"
          (add-deps-form extra-paths extra-deps)
          @rand-ns
          (if (seq global-requires)
            (format "(:require %s)" (str/join " " global-requires))
            "")
          @rand-ns @rand-ns
          (pr-str init)
          (requires-form requires)
          prog))

(defn target-order
  ([tasks task-name] (target-order tasks task-name (volatile! #{}) #{}))
  ([tasks task-name processed processing]
   (let [task (tasks task-name)
         depends (:depends task)]
     (when (contains? processing task-name)
       (throw (ex-info (str "Cyclic task: " task-name) {})))
     (let [deps (seq depends)
           deps (remove #(contains? @processed %) deps)
           order (vec (mapcat #(target-order tasks % processed (conj processing task-name)) deps))]
       (if-not (contains? @processed task-name)
         (do (vswap! processed conj task-name)
             (conj order task-name))
         order)))))

#_(defn tasks->dependees [task-names tasks]
    (let [tasks->depends (zipmap task-names (map #(:depends (get tasks %)) task-names))]
      (persistent!
       (reduce (fn [acc [task depends]]
                 (reduce (fn [acc dep]
                           (assoc! acc dep (conj (or (get acc dep)
                                                     #{})
                                                 task)))
                         acc depends)) (transient {}) tasks->depends))))

(defn assemble-task [task-name parallel?]
  (let [task-name (symbol task-name)
        bb-edn @bb-edn
        tasks (get bb-edn :tasks)
        enter (:enter tasks)
        leave (:leave tasks)
        task (get tasks task-name)]
    (binding [*print-meta* true
              *cli-target?* (boolean (cli-node task))]
      (if task
        (let [m? (map? task)
              global-requires (get tasks :requires)
              init (get tasks :init)
              prog (if (when m? (:depends task))
                     (let [[targets error]
                           (try [(target-order tasks task-name)]
                                (catch clojure.lang.ExceptionInfo e
                                  [nil (ex-message e)]))
                           task-map (cond-> {}
                                      enter (assoc :enter enter)
                                      leave (assoc :leave leave)
                                      parallel? (assoc :parallel parallel?))]
                       (if error
                         [(binding [*out* *err*]
                            (println error)) 1]
                         (loop [prog ""
                                targets (seq targets)
                                done []
                                extra-paths []
                                extra-deps nil
                                requires []]
                           (let [t (first targets)
                                 targets (next targets)
                                 task-map (assoc task-map
                                                 :name t)]
                             (if targets
                               (if-let [task (get tasks t)]
                                 (recur (str prog "\n" (assemble-task-1 task-map task parallel?))
                                        targets
                                        (conj done t)
                                        (concat extra-paths (:extra-paths task))
                                        (merge extra-deps (:extra-deps task))
                                        (concat requires (:requires task)))
                                 [(binding [*out* *err*]
                                    (println "No such task:" t)) 1])
                               (if-let [bad-dep (when (cli-node (get tasks t))
                                                  (some #(let [d (get tasks %)]
                                                           (when (and (:cmd d) (not (:task d))) %))
                                                        done))]
                                 [(binding [*out* *err*]
                                    (println (str "Task " t ": :depends cannot name " bad-dep
                                                  ", a :cmd task has no single handler to run"))) 1]
                                 (if-let [task (get tasks t)]
                                 (let [cli-prelude? (cli-node task)
                                       dep-forms prog
                                       dep-nodes (keep #(when-let [n (cli-node (get tasks %))]
                                                          [(str %) n])
                                                       done)
                                       prog (if cli-prelude?
                                              (assemble-task-1 task-map task parallel? true
                                                               (cond-> dep-forms
                                                                 parallel?
                                                                 (str "\n" (wait-tasks (:depends task))))
                                                               dep-nodes)
                                              (str dep-forms "\n"
                                                   #_(wait-tasks depends) #_(apply str (map deref-task depends))
                                                   "\n"
                                                   (assemble-task-1 task-map task parallel? true)))
                                       extra-paths (concat extra-paths (:extra-paths task))
                                       extra-deps (merge extra-deps (:extra-deps task))
                                       requires (concat requires (:requires task))]
                                   [[(format-task init extra-paths extra-deps global-requires requires prog)] nil])
                                 [(binding [*out* *err*]
                                    (println "No such task:" t)) 1])))))))
                     [[(format-task
                        init
                        (:extra-paths task)
                        (:extra-deps task)
                        global-requires
                        (:requires task)
                        (assemble-task-1 (cond-> {:name task-name}
                                           enter (assoc :enter enter)
                                           leave (assoc :leave leave)
                                           parallel? (assoc :parallel parallel?))
                                         task parallel? true))] nil])]
          (when @debug
            (binding [*out* *err*]
              (println (ffirst prog))))
          prog)
        [(binding [*out* *err*]
           (println "No such task:" task-name)) 1]))))

(defn doc-from-task
  "The task's `:doc`, or the docstring of the fn it points at. A doc is
  best-effort: deriving it loads the fn's namespace, which can fail on a stale
  bb.edn, and neither `bb tasks` nor completion may die over a missing
  docstring."
  [sci-ctx tasks task]
  (or (:doc task)
      (when-let [fn-sym (some #(when (qualified-symbol? %) %)
                              [task (:exec-fn task) (:task task)])]
        (let [requires (:requires tasks)
              requires (map (fn [x]
                              (list 'quote x))
                            (concat requires (:requires task)))
              ;; a namespace that prints when it loads must not end up in the
              ;; output: `bb tasks` would interleave it with the listing and
              ;; shell completion would offer it as a candidate
              prog (format "
(binding [*out* (java.io.StringWriter.)]
;; the fn may live on the task's own classpath
%s
;; first try to require the fully qualified namespace, as this is the cheapest option
(try (require '%s)
  ;; on failure, the namespace might have been an alias so we require other namespaces
  (catch Exception _ %s))
(:doc (meta (resolve '%s))))"
                           (add-deps-form (:extra-paths task) (:extra-deps task))
                           (namespace fn-sym)
                           (if (seq requires)
                             (list* 'require requires)
                             "")
                           fn-sym)]
          (try (sci/eval-string* sci-ctx prog)
               (catch Exception _ nil))))))

(defn key-order [edn]
  (let [forms (parser/parse-string-all edn)
        the-map (some #(when (= :map (node/tag %))
                         %)
                      (:children forms))
        loc (zip/edn the-map)
        loc (zip/down loc)
        loc (zip/find-value loc :tasks)
        loc (zip/right loc)
        loc (zip/down loc)]
    (into []
          (comp
           (take-nth 2)
           (take-while #(not (zip/end? %)))
           (filter zip/sexpr-able?)
           (map zip/sexpr)
           (filter symbol?))
          (iterate zip/right loc))))

(defn completion-program
  "Builds a SCI program (string) emitting zsh completion candidates for the bb
  task runner, given completion state already resolved by bb's own arg parsing:
  `{:sub :shell :partial :run :command-line-args :global-opts}`. `:run` is the
  task (nil when the task name itself is being completed); `:command-line-args`
  are the task's args before the cursor; `:partial` is the word being completed;
  `:global-opts` are bb's own `[flag doc]` pairs, passed in so that the help
  text stays their only definition.

  Task-name completion is done here; per-task completion is delegated to
  `babashka.cli/dispatch` over the task's node."
  [sci-ctx {:keys [sub shell run command-line-args partial global-opts]}]
  (let [shell (or shell "zsh")
        tasks (:tasks @bb-edn)]
    (case sub
      "snippet"
      ;; reuse babashka.cli's stub generator, registered for `bb`
      (format "(babashka.cli/dispatch {} [\"org.babashka.cli/completions\" \"snippet\" \"--shell\" %s \"--prog\" \"bb\"] {})"
              (pr-str shell))

      "complete"
      (if run
        ;; completing a task's options / subcommands
        (let [compl (-> ["org.babashka.cli/completions" "complete" "--shell" shell "--"]
                        (into command-line-args)
                        (conj partial))
              tm (get tasks (symbol run))
              prog (str "bb " run)]
          (if-let [node (cli-node tm)]
            ;; The task's classpath and requires run first, inside the same
            ;; try: the handler may live on its :extra-paths, and its symbol may
            ;; go through a `:requires` alias, which requiring-resolve only sees
            ;; once the require has run. Both can fail on a stale bb.edn, and no
            ;; failure here may surface: the shell discards stderr, so an
            ;; uncaught error would look like "no candidates" while also
            ;; suppressing the file-completion fallback
            (format "(try (let [tree (binding [*out* (java.io.StringWriter.)] %s\n%s\n(babashka.tasks/-resolve-cli-specs requiring-resolve (babashka.tasks/-task-node requiring-resolve \"%s\" %s)))] (babashka.cli/dispatch tree %s (merge %s (babashka.tasks/-resolve-cli-opts requiring-resolve '%s \":tasks :cli\") %s))) (catch Throwable _ (println \"org.babashka.cli/file-completion\")))"
                    (add-deps-form (:extra-paths tm) (:extra-deps tm))
                    (requires-form (concat (:requires tasks) (:requires tm)))
                    run
                    (pr-str (list 'quote node)) (pr-str compl)
                    ;; same defaults as -cli-dispatch, in the same precedence:
                    ;; a runner-level :cli (incl. a symbol naming a defaults
                    ;; var) may turn :help off, and :prog stays bb's
                    (pr-str {:help true})
                    (pr-str (:cli tasks))
                    (pr-str {:prog prog}))
            ;; a task without :cli has no options to offer; defer to the shell
            ;; so file completion still works, as it did before bb claimed the
            ;; `bb` compdef
            "(println \"org.babashka.cli/file-completion\")"))
        ;; completing the task name itself. A dash-prefixed word completes bb's
        ;; global options; a fresh word completes task names plus files (marker
        ;; line defers to the shell): `bb file.clj` is as first-class as `bb task`
        (let [lines (if (str/starts-with? partial "-")
                      (keep (fn [[flag desc]]
                              (when (str/starts-with? flag partial)
                                (str flag "\t" desc)))
                            global-opts)
                      (-> (->> tasks
                               (keep (fn [[k v]]
                                       (let [n (str k)]
                                         (when (and (symbol? k)
                                                    (not (str/starts-with? n "-"))
                                                    (not (:private v))
                                                    (str/starts-with? n partial))
                                           ;; one candidate is one line: the
                                           ;; rest of a multi-line doc would
                                           ;; each become a candidate of their
                                           ;; own. list-tasks truncates too
                                           (let [d (some-> (doc-from-task sci-ctx tasks v)
                                                           str/split-lines
                                                           first)]
                                             (if (str/blank? d) n (str n "\t" d)))))))
                               sort
                               vec)
                          (conj "org.babashka.cli/file-completion")))]
          (pr-str (cons 'do (map #(list 'println %) lines)))))

      ;; default: an unknown sub emits a program that does nothing
      "nil")))

(defn list-tasks
  "Prints out the task names found in BB-EDN in the original order
  alongside their documentation as retrieved with SCI-CTX.

  For a task to be listed
  - its name has to be a symbol but should not start with `-`, and
  - should not be `:private`."
  [sci-ctx]
  (let [tasks (:tasks @bb-edn)
        raw-edn (:raw @bb-edn)
        names (when (seq tasks)
                (->> (key-order raw-edn)
                     (map str)
                     (remove #(str/starts-with? % "-"))
                     (remove #(:private (get tasks (symbol %))))))]
    (if (seq names)
      (let [longest (apply max (map count names))
            fmt (str "%1$-" longest "s")]
        (println "The following tasks are available:")
        (println)
        (doseq [k names
                :let [task (get tasks (symbol k))]]
          (println (str (format fmt k)
                        (when-let [d (doc-from-task sci-ctx tasks task)]
                          (let [first-line (-> (str/split-lines d)
                                               first)]
                            (str "  " first-line)))))))
      (println "No tasks found."))))

(defn run
  ([task] (run task nil))
  ([task {:keys [:parallel]
          :or {parallel (:parallel (current-task))}}]
   (let [[[expr] exit-code] (assemble-task task parallel)]
     (if (or (nil? exit-code) (zero? exit-code))
       (sci/eval-string* (ctx) expr)
       (throw (ex-info nil
                       {:babashka/exit exit-code}))))))

(defn exec
  ([sym]
   (let [snippet (cli/exec-fn-snippet sym)]
     (sci/eval-string* (ctx) snippet)))
  ([sym extra-opts]
   (let [snippet (cli/exec-fn-snippet sym extra-opts)]
     (sci/eval-string* (ctx) snippet))))

(def tasks-namespace
  {'shell (sci/copy-var shell sci-ns)
   'clojure (sci/copy-var clojure sci-ns)
   '-wait (sci/copy-var -wait sci-ns)
   '-chan? (sci/copy-var -chan? sci-ns)
   '-err-thread (sci/copy-var -err-thread sci-ns)
   '*task* task
   'current-task current-task
   'current-state state
   'run (sci/copy-var run sci-ns)
   'exec (sci/copy-var exec sci-ns)
   '-cli-dispatch (sci/copy-var -cli-dispatch sci-ns)
   '-run-cli-dep (sci/copy-var -run-cli-dep sci-ns)
   '-dep-node (sci/copy-var -dep-node sci-ns)
   '-resolve-cli-specs (sci/copy-var -resolve-cli-specs sci-ns)
   '-resolve-cli-opts (sci/copy-var -resolve-cli-opts sci-ns)
   '-task-node (sci/copy-var -task-node sci-ns)
   #_#_'log log})
