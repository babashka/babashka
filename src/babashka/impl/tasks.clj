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

(defn -cli-dispatch
  "Runs babashka.cli/dispatch over a task's `:cli` tree. `body-fn` (the task
  body wrapped as a fn, or nil when the task has no body) becomes the root
  `:fn`. A node's `:fn` symbol (root or subcommand) is resolved via `resolve-fn`
  (the script's `requiring-resolve`), and the `:org.babashka/cli` metadata of
  its namespace and of the var (`:spec`, `:args->opts`, `:restrict`, `:epilog`,
  ...) merges into the node, like `bb -x` - so the spec and help live with the
  fn. Var metadata wins over ns metadata; explicit node keys win over both. The
  fn is called with dispatch's result map (`{:opts ... :dispatch ... :args ...}`),
  like any babashka.cli/dispatch `:fn`.

  `deps-fn` (or nil) is the task's assembled `:depends` as a thunk. It runs
  right before whichever command fn the parser selects - root body or a
  subcommand - and only on a successful parse. dispatch never calls a command
  fn for `--help`/`-h` or a parse error, so the deps run exactly when the body
  runs, decided by the parser (like cobra's PreRun), not by scanning raw args."
  ([cli-opts task-name body-fn resolve-fn args]
   (-cli-dispatch cli-opts task-name body-fn nil resolve-fn args))
  ([cli-opts task-name body-fn deps-fn resolve-fn args]
   (let [with-deps (fn [f] (fn [m] (when deps-fn (deps-fn)) (f m)))
         ;; resolve a :fn / :exec-fn symbol, merge the ns and var
         ;; :org.babashka/cli metadata and the docstring (like bb -x; node keys
         ;; win), and gate :depends on the fn being called
         wrap-key (fn [node k]
                    (if-let [fv (k node)]
                      (let [the-var (if (symbol? fv) (resolve-fn fv) fv)
                            m (when (symbol? fv) (meta the-var))]
                        (-> (babashka.cli/merge-opts
                             (:org.babashka/cli (meta (:ns m)))
                             (:org.babashka/cli m)
                             (when-let [d (:doc m)] {:doc d})
                             node)
                            (assoc k (with-deps (fn [m] (the-var m))))))
                      node))
         wrap (fn wrap [node]
                (let [node (-> node (wrap-key :fn) (wrap-key :exec-fn))]
                  (if-let [cm (:cmd node)]
                    (assoc node :cmd (into {} (map (fn [[k v]] [k (wrap v)])) cm))
                    node)))
         tree (wrap cli-opts)
         tree (if body-fn (assoc tree :fn (with-deps body-fn)) tree)
         ;; a `:cli` entry in the :tasks map (like :requires/:init) provides
         ;; defaults for every :cli task, e.g. {:restrict true}. dispatch
         ;; merges its opts into every tree node; node keys win.
         defaults (:cli (:tasks @bb-edn))]
     (babashka.cli/dispatch tree args (merge defaults {:help true :prog (str "bb " task-name)})))))

(defn -resolve-cli-specs
  "Walk a `:cli` tree, merging each node fn's `:org.babashka/cli` metadata and
  docstring into its node (explicit node keys win), for both `:fn` and
  `:exec-fn`. `resolve-fn` is the script's `requiring-resolve`. Used where the
  tree is inspected but the fns are not called - `--help` and shell completion -
  so a node's spec and doc show up even though they live on the fn. Mirrors the
  merge in -cli-dispatch's wrap."
  [resolve-fn node]
  (let [fv (or (:fn node) (:exec-fn node))
        node (if (symbol? fv)
               (let [m (meta (resolve-fn fv))]
                 (babashka.cli/merge-opts
                  (:org.babashka/cli (meta (:ns m)))
                  (:org.babashka/cli m)
                  (when-let [d (:doc m)] {:doc d})
                  node))
               node)]
    (if-let [cm (:cmd node)]
      (assoc node :cmd (into {} (map (fn [[k v]] [k (-resolve-cli-specs resolve-fn v)])) cm))
      node)))

(defn wrap-cli
  "When a task declares `:cli`, route its invocation through
  babashka.cli/dispatch: parses options (exposed as `:opts` on `*task*` for the
  body), handles `--help` and subcommands.

  `dep-forms` (the task's assembled `:depends`, or nil) are passed to
  -cli-dispatch as a thunk and run before whichever command fn the parser
  selects (root body or subcommand), only on a successful parse - never on
  `--help`/`-h` or a parse error (like cobra's PreRun), rather than by scanning
  raw args for a help token.

  Task-map `:doc` folds into the tree root so `--help` and `bb tasks` agree
  (an explicit `:doc` in the `:cli` map wins)."
  ([task-map prog] (wrap-cli task-map prog nil))
  ([task-map prog dep-forms]
   (if-let [cli-opts (:cli task-map)]
     (format "(babashka.tasks/-cli-dispatch '%s \"%s\" %s %s requiring-resolve *command-line-args*)"
             (pr-str (merge (select-keys task-map [:doc]) cli-opts))
             (:name task-map)
             (if (:task task-map)
               (format "(fn [{:keys [opts]}] (binding [babashka.tasks/*task* (assoc babashka.tasks/*task* :opts opts)] %s))"
                       prog)
               "nil")
             (if dep-forms
               (format "(fn [] %s)" dep-forms)
               "nil"))
     prog)))

(defn assemble-task-1
  "Assembles task, does not process :depends. `dep-forms` is only threaded for a
  `:cli` target (see wrap-cli): assembled `:depends` to run inside the body fn."
  ([task-map task parallel?]
   (assemble-task-1 task-map task parallel? nil nil))
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
          (let [deps (cond-> {}
                       (seq extra-deps) (assoc :deps extra-deps)
                       (seq extra-paths) (assoc :paths extra-paths))]
            (if (seq deps)
              (format "(babashka.deps/add-deps '%s)" (pr-str deps))
              ""))
          @rand-ns
          (if (seq global-requires)
            (format "(:require %s)" (str/join " " global-requires))
            "")
          @rand-ns @rand-ns
          (pr-str init)
          (if (seq requires)
            (format "(require %s)"
                    (str/join "\n"
                              (map (fn [req]
                                     (str "'" req))
                                   requires)))
            "")
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
                                 (let [dep-forms prog
                                       ;; For a non-parallel `:cli` task, hand the
                                       ;; deps to dispatch as a thunk (wrap-cli),
                                       ;; so they run only when a command fn runs
                                       ;; - never on `--help` or a parse error.
                                       ;; Otherwise keep the deps as forms before
                                       ;; the target (parallel deps rely on
                                       ;; launching their channels ahead of the
                                       ;; target's wait).
                                       cli-prelude? (and (:cli task) (not parallel?))
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

(defn doc-from-task [sci-ctx tasks task]
  (or (:doc task)
      (when-let [fn-sym (cond (qualified-symbol? task)
                              task
                              (map? task)
                              (or (let [f (or (:fn (:cli task))
                                              (:exec-fn (:cli task)))]
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
;; first try to require the fully qualified namespace, as this is the cheapest option
(try (require '%s)
  ;; on failure, the namespace might have been an alias so we require other namespaces
  (catch Exception _ %s))
(:doc (meta (resolve '%s)))"
                           (namespace fn-sym)
                           (if (seq requires)
                             (list* 'require requires)
                             "")
                           fn-sym)]
          (sci/eval-string* sci-ctx prog)))))

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

(def global-opt-completions
  "bb's global and evaluation options offered when completing a dash-prefixed
  first word. Curated from print-help; keep in sync."
  [["--classpath" "Classpath to use. Overrides bb.edn classpath"]
   ["-cp" "Classpath to use. Overrides bb.edn classpath"]
   ["--config" "Replace bb.edn with file"]
   ["--deps-root" "Treat dir as root of relative paths in config"]
   ["--debug" "Print debug information and internal stacktrace in case of exception"]
   ["--init" "Load file after any preloads and prior to evaluation/subcommands"]
   ["--prn" "Print result via clojure.core/prn"]
   ["--force-exit" "Force exiting even when non-daemon threads are still running"]
   ["-Sforce" "Force recalculation of the classpath (don't use the cache)"]
   ["-Sdeps" "Deps data to use as the last deps file to be merged"]
   ["--file" "Run file"]
   ["-f" "Run file"]
   ["--jar" "Run uberjar"]
   ["--eval" "Evaluate an expression"]
   ["-e" "Evaluate an expression"]
   ["--main" "Call the -main function from a namespace or call a fully qualified var"]
   ["-m" "Call the -main function from a namespace or call a fully qualified var"]
   ["--exec" "Call the fully qualified var. Args are parsed by babashka CLI"]
   ["-x" "Call the fully qualified var. Args are parsed by babashka CLI"]
   ["--version" "Print the current version of babashka"]
   ["--help" "Print help text"]
   ["-h" "Print help text"]])

(defn completion-program
  "Builds a SCI program (string) emitting zsh completion candidates for the bb
  task runner, given completion state already resolved by bb's own arg parsing:
  `{:sub :shell :partial :run :command-line-args}`. `:run` is the task (nil when
  the task name itself is being completed); `:command-line-args` are the task's
  args before the cursor; `:partial` is the word being completed.

  Task-name completion is done here; per-task option/subcommand completion is
  delegated to `babashka.cli/dispatch` over the task's `:cli` tree (with each
  node fn's `:org.babashka/cli` metadata merged in), reusing dispatch's own
  completion machinery."
  [sci-ctx {:keys [sub shell run command-line-args partial]}]
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
          (if (:cli tm)
            (format "(babashka.cli/dispatch (babashka.tasks/-resolve-cli-specs requiring-resolve %s) %s %s)"
                    (pr-str (list 'quote (:cli tm))) (pr-str compl)
                    ;; same defaults as -cli-dispatch, so e.g. a shared :spec
                    ;; completes here too
                    (pr-str (merge (:cli tasks) {:prog prog :help true})))
            "nil"))
        ;; completing the task name itself. A dash-prefixed word completes bb's
        ;; global options; a fresh word completes task names plus files (marker
        ;; line defers to the shell): `bb file.clj` is as first-class as `bb task`
        (let [lines (if (str/starts-with? partial "-")
                      (keep (fn [[flag desc]]
                              (when (str/starts-with? flag partial)
                                (str flag "\t" desc)))
                            global-opt-completions)
                      (-> (->> tasks
                               (keep (fn [[k v]]
                                       (let [n (str k)]
                                         (when (and (symbol? k)
                                                    (not (str/starts-with? n "-"))
                                                    (not (and (map? v) (:private v)))
                                                    (str/starts-with? n partial))
                                           (let [d (doc-from-task sci-ctx tasks v)]
                                             (if d (str n "\t" d) n))))))
                               sort
                               vec)
                          (conj "org.babashka.cli/file-completion")))]
          (format "(do %s)"
                  (str/join " " (map #(format "(println %s)" (pr-str %)) lines)))))

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
   #_#_'log log})
