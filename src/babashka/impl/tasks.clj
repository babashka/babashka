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

(defn- update-task-maps
  "Applies `f` to every task in `edn` written as a map, as `(f name task)`.
  Entries like `:init` and `:requires`, and tasks written as a bare form, are
  left alone."
  [edn f]
  (if-let [tasks (:tasks edn)]
    (assoc edn :tasks
           (reduce-kv (fn [acc k v]
                        (if (and (symbol? k) (map? v))
                          (assoc acc k (f k v))
                          acc))
                      tasks tasks))
    edn))

(defn- assert-cmd-maps
  "A `:cmd` is a map of command name to command, or a vector of
  `[name command]` pairs when the order matters, and every command in it is a
  map. Pointing a command straight at a function reads like it should work, so
  say what to write instead of letting it fail somewhere down the tree."
  [task-name cmd]
  (when (some? cmd)
    (when-not (or (map? cmd) (vector? cmd))
      (throw (ex-info (str "Task " task-name
                           ": :cmd must be a map of command name to command, or a vector of [name command] pairs, got: "
                           (pr-str cmd))
                      {:babashka/exit 1})))
    ;; a map entry and a [name command] pair destructure alike
    (run! (fn [[name node]]
            (when-not (map? node)
              (throw (ex-info (str "Task " task-name ": :cmd " (pr-str name)
                                   " must be a map, got: " (pr-str node)
                                   ". Write {:exec-fn " (pr-str node)
                                   "} to point a command at a function")
                              {:babashka/exit 1})))
            (assert-cmd-maps task-name (:cmd node)))
          cmd)))

(defn cli-node
  "The babashka.cli dispatch node for a task, or nil when the task is a plain
  one. Naming a handler (`:exec-fn`, `:fn`) or a command tree (`:cmd`) is what
  opts a task in, and the task map is then the node itself. dispatch ignores
  bb's own keys, so node options like `:epilog` work where they are written,
  with no list of blessed keys to keep up to date."
  [task-map]
  (when (and (map? task-map)
             (or (:exec-fn task-map) (:fn task-map) (:cmd task-map)))
    task-map))

(def ^:private cli-only-keys
  "babashka.cli node keys that do nothing on a task that is not a CLI task.
  `:doc` is not among them, every task may have one."
  [:spec :args->opts :epilog :restrict :restrict-args :coerce :cmd-order :order])

(defn validate-tasks
  "Rejects task shapes that cannot do what they look like they do. Returns
  `edn` unchanged, it only reads."
  [edn]
  (doseq [[k v] (:tasks edn)
          :when (and (symbol? k) (map? v))]
    (when (:cli v)
      (throw (ex-info (str "Task " k ": :cli is not a task key. Write :exec-fn, :fn, :cmd"
                           " and any babashka.cli options directly on the task")
                      {:babashka/exit 1})))
    (assert-cmd-maps k (:cmd v))
    ;; a `:task` body is the root handler, so a `:fn` next to it never runs.
    ;; `:exec-fn` is allowed there, it takes priority over a body on purpose
    (when (and (:task v) (:fn v))
      (throw (ex-info (str "Task " k ": both a :task body and a :fn given"
                           ". The body is the root handler, use :exec-fn to override it")
                      {:babashka/exit 1})))
    (when-not (cli-node v)
      (when-let [stray (seq (filter v cli-only-keys))]
        (throw (ex-info (str "Task " k ": " (str/join ", " stray)
                             " needs a handler or a command tree. Add :exec-fn, :fn or :cmd")
                        {:babashka/exit 1})))))
  edn)

(defn join-docs
  "A task `:doc` may be a vector of lines, convenient in edn. Joins it into
  the string every consumer expects."
  [edn]
  (update-task-maps edn (fn [_ v]
                          (cond-> v
                            (vector? (:doc v)) (update :doc #(str/join "\n" %))))))

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

(defn -resolve-cli-tasks-defaults
  "The runner-level `:tasks {:cli ...}` entry, resolved to a map: a map as-is,
  or a symbol naming a def of one, resolved via `resolve-fn` (the script's
  `requiring-resolve`) - the symbol form is for defaults that include
  functions, like `:error-fn`. nil when there is no entry. Throws when the
  entry is neither nil, a map, nor a symbol resolving to a map."
  [resolve-fn d]
  (cond
    (nil? d) nil
    (map? d) d
    (symbol? d) (let [v (resolve-or-throw resolve-fn d
                                          (str ":tasks :cli " d " cannot be resolved"))
                      m (deref v)]
                  (when-not (map? m)
                    (throw (ex-info (str ":tasks :cli " d " is not a map")
                                    {:babashka/exit 1})))
                  m)
    :else (throw (ex-info (str ":tasks :cli must be a map or a symbol naming a def, got: " (pr-str d))
                          {:babashka/exit 1}))))

(defn- map-cmd
  "Applies `f` to every command in a `:cmd`, keeping its shape. A vector of
  `[name command]` pairs is how babashka.cli takes an ordered command list, so
  rebuilding it as a map would throw that order away."
  [cmd f]
  (let [entries (map (fn [[name node]] [name (f node)]) cmd)]
    (if (vector? cmd) (vec entries) (into {} entries))))

(defn- assert-no-edn-error-fn
  "In bb.edn a `:error-fn` can only be data; dispatch would invoke a symbol as
  a map lookup and silently swallow every error. It belongs in the function's
  `:org.babashka/cli` metadata, or in a defaults var referenced from
  `:tasks {:cli my.ns/defaults}`."
  [node task-name]
  (when (contains? node :error-fn)
    (throw (ex-info (str "Task " task-name ": :error-fn is not supported in bb.edn, put it in the function's :org.babashka/cli metadata or in a var referenced from :tasks {:cli my.ns/defaults}")
                    {:babashka/exit 1})))
  ;; `map second` and not `vals`: a :cmd is a map or a vector of pairs
  (run! #(assert-no-edn-error-fn % task-name) (map second (:cmd node))))

(defn -cli-dispatch
  "Runs babashka.cli/dispatch over a task's `:cli` tree. `body-fn` (the task
  body wrapped as a fn, or nil when the task has no body) becomes the root
  `:fn`. A node's `:fn` symbol (root or subcommand) is resolved via `resolve-fn`
  (the script's `requiring-resolve`), and the `:org.babashka/cli` metadata of
  its namespace and of the var (`:spec`, `:args->opts`, `:restrict`, `:epilog`,
  ...) merges into the node, like `bb -x` - so the spec and help live with the
  fn. A `:cmd` tree on the fn is ignored: command trees belong in bb.edn. Var
  metadata wins over ns metadata; explicit node keys win over both. The
  fn is called with dispatch's result map (`{:opts ... :dispatch ... :args ...}`),
  like any babashka.cli/dispatch `:fn`.

  `fns` holds the parts of the task that must not run until the parser picks a
  command: `:body-fn` (the task body, or nil), `:deps-fn` (the assembled
  `:depends` bodies, or nil) and `:hook-fn` (`:enter` / `:leave` around a call,
  or nil). A dependency's own `:requires` and `:extra-paths` / `:extra-deps`
  are not in here: they stay in the program preamble, because sci analyzes this
  thunk as one form and a require inside it would not have run when a body
  using its alias is analyzed (see ADR 0001, decision 11).
  `:deps-fn` and `:hook-fn` run right before whichever command fn the
  parser selects - root body or a subcommand - and only on a successful parse.
  dispatch never calls a command fn for `--help`/`-h` or a parse error, so
  nothing in `fns` runs then, decided by the parser (like cobra's PreRun) rather
  than by scanning raw args. `:body-fn` carries its own `:enter` / `:leave`,
  so `:hook-fn` applies only to the resolved `:fn` / `:exec-fn` handlers."
  [cli-opts task-name fns resolve-fn args]
  (assert-no-edn-error-fn cli-opts task-name)
  (when (map? (:cli (:tasks @bb-edn)))
    (assert-no-edn-error-fn (:cli (:tasks @bb-edn)) task-name))
  (let [{:keys [body-fn deps-fn hook-fn]} fns
        with-deps (fn [f] (fn [m] (when deps-fn (deps-fn)) (f m)))
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
                 (if-let [cm (:cmd node)]
                   (assoc node :cmd (map-cmd cm wrap))
                   node)))
        tree (wrap cli-opts)
        tree (if body-fn (assoc tree :fn (with-deps body-fn)) tree)
        ;; a `:cli` entry in the :tasks map (like :requires/:init) provides
        ;; dispatch defaults for every CLI task, merged into the dispatch
        ;; opts; node keys win. `:prog` stays bb's, so help always names the
        ;; task it belongs to.
        defaults (-resolve-cli-tasks-defaults resolve-fn (:cli (:tasks @bb-edn)))]
    (babashka.cli/dispatch tree args (merge {:help true}
                                            defaults
                                            {:prog (str "bb " task-name)}))))

(defn -resolve-cli-specs
  "Walk a `:cli` tree, folding each node fn's metadata into its node with
  fold-fn-meta, for both `:fn` and `:exec-fn`. `resolve-fn` is the script's
  `requiring-resolve`. Used where the tree is inspected but the fns are not
  called - `--help` and shell completion - so a node's spec and doc show up even
  though they live on the fn. Unlike -cli-dispatch this does not insist that a
  symbol resolves: a stale name should not stop the rest from being described."
  [resolve-fn node]
  (let [merge-fn-meta (fn [node k]
                        (let [fv (k node)]
                          (fold-fn-meta (when (symbol? fv) (meta (resolve-fn fv)))
                                        node)))
        node (-> node (merge-fn-meta :fn) (merge-fn-meta :exec-fn))]
    (if-let [cm (:cmd node)]
      (assoc node :cmd (map-cmd cm #(-resolve-cli-specs resolve-fn %)))
      node)))

(defn wrap-cli
  "When a task declares `:cli`, route its invocation through
  babashka.cli/dispatch: parses options (exposed as `:opts` on `*task*` for the
  body), handles `--help` and subcommands.

  `dep-forms` (the task's assembled `:depends`, or nil) and `:enter` / `:leave`
  are handed over as thunks, for -cli-dispatch to run once the parser picks a
  command. A `:task` body is already wrapped by wrap-enter-leave, so the hook
  thunk is for the handler case.

  The task map is the dispatch node, so its `:doc` is the tree root's `:doc`
  and `--help` and `bb tasks` agree without folding anything in. A `:task` body
  becomes the root handler and is called with no arguments: parsed options are
  what `:exec-fn` is for."
  ([task-map prog] (wrap-cli task-map prog nil))
  ([task-map prog dep-forms]
   (if-let [cli-opts (cli-node task-map)]
     (let [{:keys [enter leave name]} task-map]
       (format "(babashka.tasks/-cli-dispatch '%s \"%s\" {:body-fn %s :deps-fn %s :hook-fn %s} requiring-resolve *command-line-args*)"
               (pr-str cli-opts)
               name
               (if (:task task-map)
                 (format "(fn [_] %s)" prog)
                 "nil")
               (if dep-forms
                 (format "(fn [] %s)" dep-forms)
                 "nil")
               (if (or enter leave)
                 (format "(fn [thunk] %s)" (wrap-enter-leave name "(thunk)" enter leave))
                 "nil")))
     prog)))

(defn assemble-task-1
  "Assembles task, does not process :depends. `dep-forms` is only threaded for a
  `:cli` target (see wrap-cli): assembled `:depends` to run inside the body fn."
  ([task-map task parallel?]
   (assemble-task-1 task-map task parallel? nil))
  ([task-map task parallel? last?]
   (assemble-task-1 task-map task parallel? last? nil))
  ([task-map task parallel? last? dep-forms]
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
                    task-map)]
     (cond
       (qualified-symbol? task)
       (let [prog (format "(apply %s *command-line-args*)" task)
             prog (wrap-enter-leave task-name prog enter leave)
             ;; a `:cli` task keeps its dispatch even when the body is a
             ;; qualified symbol: the symbol call becomes the default action
             prog (if last? (wrap-cli task-map prog dep-forms) prog)
             prog (wrap-depends prog depends parallel?)
             prog (wrap-def task-map prog parallel? last?)
             prog (format "
(when-not (resolve '%s) (require (quote %s)))
%s"
                          task
                          (namespace task)
                          prog)]
         prog)

       :else
       (let [prog (pr-str task)
             prog (wrap-enter-leave task-name prog enter leave)
             prog (if last? (wrap-cli task-map prog dep-forms) prog)
             prog (wrap-depends prog depends parallel?)
             prog (wrap-def task-map prog parallel? last?)]
         prog)))))

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
    (binding [*print-meta* true]
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
                               (if-let [task (get tasks t)]
                                 (let [;; a non-parallel `:cli` task takes its dep
                                       ;; bodies as a thunk (see ADR 0001,
                                       ;; decision 11); parallel deps stay ahead
                                       ;; of the target, they must launch their
                                       ;; channels before it waits
                                       cli-prelude? (and (cli-node task) (not parallel?))
                                       dep-forms prog
                                       prog (if cli-prelude?
                                              (assemble-task-1 task-map task parallel? true dep-forms)
                                              (str dep-forms "\n"
                                                   #_(wait-tasks depends) #_(apply str (map deref-task depends))
                                                   "\n"
                                                   (assemble-task-1 task-map task parallel? true)))
                                       extra-paths (concat extra-paths (:extra-paths task))
                                       extra-deps (merge extra-deps (:extra-deps task))
                                       requires (concat requires (:requires task))]
                                   [[(format-task init extra-paths extra-deps global-requires requires prog)] nil])
                                 [(binding [*out* *err*]
                                    (println "No such task:" t)) 1]))))))
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
      (when-let [fn-sym (cond (qualified-symbol? task)
                              task
                              (map? task)
                              (or (let [f (or (:fn task)
                                              (:exec-fn task))]
                                    (when (qualified-symbol? f)
                                      f))
                                  (let [t (:task task)]
                                    (when (qualified-symbol? t)
                                      t))))]
        (let [requires (:requires tasks)
              requires (map (fn [x]
                              (list 'quote x))
                            (concat requires (:requires task)))
              prog (format "
;; the fn may live on the task's own classpath
%s
;; first try to require the fully qualified namespace, as this is the cheapest option
(try (require '%s)
  ;; on failure, the namespace might have been an alias so we require other namespaces
  (catch Exception _ %s))
(:doc (meta (resolve '%s)))"
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

  Task-name completion is done here; per-task option/subcommand completion is
  delegated to `babashka.cli/dispatch` over the task's `:cli` tree (with each
  node fn's `:org.babashka/cli` metadata merged in), reusing dispatch's own
  completion machinery."
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
            (format "(try %s\n%s\n(babashka.cli/dispatch (babashka.tasks/-resolve-cli-specs requiring-resolve %s) %s (merge %s (babashka.tasks/-resolve-cli-tasks-defaults requiring-resolve '%s) %s)) (catch Throwable _ (println \"org.babashka.cli/file-completion\")))"
                    (add-deps-form (:extra-paths tm) (:extra-deps tm))
                    (requires-form (concat (:requires tasks) (:requires tm)))
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
                                                    (not (and (map? v) (:private v)))
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
          (format "(do %s)"
                  (str/join " " (map #(format "(println %s)" (pr-str %)) lines)))))

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
                            (str " " first-line)))))))
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
   '-resolve-cli-specs (sci/copy-var -resolve-cli-specs sci-ns)
   '-resolve-cli-tasks-defaults (sci/copy-var -resolve-cli-tasks-defaults sci-ns)
   #_#_'log log})
