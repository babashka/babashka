(ns babashka.impl.tasks
  (:require
   [babashka.deps :as deps]
   [babashka.impl.cli :as cli]
   [babashka.impl.common :refer [bb-edn ctx debug]]
   [babashka.impl.process :as pp]
   [babashka.process :as p]
   [clojure.core.async :refer [<!!]]
   [clojure.java.io :as io]
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

(defn shell [cmd & args]
  (let [[prev cmd args]
        (if (and (map? cmd)
                 (:proc cmd))
          [cmd (first args) (rest args)]
          [nil cmd args])
        [opts cmd args]
        (if (map? cmd)
          [cmd (first args) (rest args)]
          [nil cmd args])
        opts (if-let [o (:out opts)]
               (if (string? o)
                 (update opts :out io/file)
                 opts)
               opts)
        opts (if-let [o (:err opts)]
               (if (string? o)
                 (update opts :err io/file)
                 opts)
               opts)
        opts (if prev
               (assoc opts :in nil)
               opts)
        cmd (if (.exists (io/file cmd))
              [cmd]
              (p/tokenize cmd))
        cmd (into cmd args)
        local-log-level (:log-level opts)]
    (sci/binding [log-level (or local-log-level @log-level)]
      (apply log-info cmd)
      (handle-non-zero (pp/process prev cmd (merge default-opts opts)) opts))))

(defn clojure [cmd & args]
  (let [[opts cmd args]
        (if (map? cmd)
          [cmd (first args) (rest args)]
          [nil cmd args])
        opts (if-let [o (:out opts)]
               (if (string? o)
                 (update opts :out io/file)
                 opts)
               opts)
        opts (if-let [o (:err opts)]
               (if (string? o)
                 (update opts :err io/file)
                 opts)
               opts)
        cmd (if (.exists (io/file cmd))
              [cmd]
              (p/tokenize cmd))
        cmd (into cmd args)
        local-log-level (:log-level opts)]
    (sci/binding [log-level (or local-log-level @log-level)]
      (apply log-info (cons "clojure" cmd))
      (handle-non-zero (deps/clojure cmd (merge default-opts opts)) opts))))

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
    (apply  prn strs)))

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

(defn assemble-task-1
  "Assembles task, does not process :depends."
  ([task-map task parallel?]
   (assemble-task-1 task-map task parallel? nil))
  ([task-map task parallel? last?]
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
     (if (qualified-symbol? task)
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
       (let [prog (pr-str task)
             prog (wrap-enter-leave task-name prog enter leave)
             prog (wrap-depends prog depends parallel?)
             prog (wrap-def task-map prog parallel? last?)]
         prog)))))

(def rand-ns (delay (symbol (str "user-" (java.util.UUID/randomUUID)))))

(defn format-task [init extra-paths extra-deps requires prog]
  (format "
%s ;; deps

(ns %s %s)
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
  (intern *ns* (with-meta 'exec {:macro true}) @(var babashka.tasks/exec)))

%s
%s

"
          (let [deps (cond-> {}
                       (seq extra-deps) (assoc :deps extra-deps)
                       (seq extra-paths) (assoc :paths extra-paths))]
            (if (seq deps)
              (format "(babashka.deps/add-deps '%s)" (pr-str deps))
              ""))
          @rand-ns
          (if (seq requires)
            (format "(:require %s)" (str/join " " requires))
            "")
          (pr-str init)
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
              requires (get tasks :requires)
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
                                requires requires]
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
                                 (let [prog (str prog "\n"
                                                 #_(wait-tasks depends) #_(apply str (map deref-task depends))
                                                 "\n"
                                                 (assemble-task-1 task-map task parallel? true))
                                       extra-paths (concat extra-paths (:extra-paths task))
                                       extra-deps (merge extra-deps (:extra-deps task))
                                       requires (concat requires (:requires task))]
                                   [[(format-task init extra-paths extra-deps requires prog)] nil])
                                 [(binding [*out* *err*]
                                    (println "No such task:" t)) 1]))))))
                     [[(format-task
                        init
                        (:extra-paths task)
                        (:extra-deps task)
                        (concat requires (:requires task))
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
                              (let [t (:task task)]
                                (when (qualified-symbol? t)
                                  t)))]
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
           (take-nth 2 )
           (take-while #(not (zip/end? %)))
           (filter zip/sexpr-able?)
           (map zip/sexpr)
           (filter symbol?))
          (iterate zip/right loc))))

(defn list-tasks
  [sci-ctx]
  (let [tasks (:tasks @bb-edn)]
    (if (seq tasks)
      (let [raw-edn (:raw @bb-edn)
            names (key-order raw-edn)
            names (map str names)
            names (remove #(str/starts-with? % "-") names)
            names (remove #(:private (get tasks (symbol %))) names)
            longest (apply max (map count names))
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
   (let [[[expr]] (assemble-task task parallel)]
     (sci/eval-string* @ctx expr))))

(defn ^:macro exec
  ([_ _ fq-sym]
   (let [ns (namespace fq-sym)
         var-name (name fq-sym)
         snippet (cli/exec-fn-snippet ns var-name)]
     `(load-string ~snippet))))

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
   #_#_'log log})
