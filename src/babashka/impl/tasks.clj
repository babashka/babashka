(ns babashka.impl.tasks
  (:require [babashka.impl.common :refer [bb-edn]]
            [babashka.impl.deps :as deps]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as zip]
            [sci.core :as sci]))

(def sci-ns (sci/create-ns 'babashka.tasks nil))
(def default-log-level :error)
(def log-level (sci/new-dynamic-var '*-log-level* default-log-level {:ns sci-ns}))
(def task-name (sci/new-dynamic-var '*-task-name* nil {:ns sci-ns}))

(defn log-info [& strs]
  (let [log-level @log-level]
    (when
        ;; do not log when level is :error
        (identical? :info log-level)
      (binding [*out* *err*]
        (println (format "[bb %s]" @task-name) (str/join " " strs))))))

(defn log-error [& strs]
  (let [log-level @log-level]
    (when (or
           ;; log error also in case of info level
           (identical? :info log-level)
           (identical? :error log-level))
      (binding [*out* *err*]
        (println (format "[bb %s]" @task-name) (str/join " " strs))))))

(defn- handle-non-zero [proc opts]
  (when proc
    (when-let [proc (deref proc)]
      (let [exit-code (:exit proc)
            zero-exit? (zero? exit-code)
            continue (:continue opts)
            continue? (if continue
                        (or (true? continue)
                            (continue proc))
                        zero-exit?)]
        (if continue? proc
            (do (when-not zero-exit?
                  (log-error "Terminating with non-zero exit code: " exit-code))
                (System/exit exit-code)))))))

(def default-opts
  {:in :inherit
   :out :inherit
   :err :inherit
   :shutdown p/destroy-tree})

(defn shell [cmd & args]
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
      (apply log-info cmd)
      (handle-non-zero (p/process cmd (merge default-opts opts)) opts))))

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
    (if (future? res)
      @res
      res)))

(def tasks-namespace
  {'shell (sci/copy-var shell sci-ns)
   'clojure (sci/copy-var clojure sci-ns)
   '-wait (sci/copy-var -wait sci-ns)
   '*-task-name* task-name
   '*-log-level* log-level
   '-log-info (sci/copy-var log-info sci-ns)
   '-log-error (sci/copy-var log-error sci-ns)})

(defn depends-map [tasks target-name]
  (let [deps (seq (:depends (get tasks target-name)))
        m [target-name deps]]
    (into {} (cons m (map #(depends-map tasks %) deps)))))

#_(defn wrap-when [expr when-expr]
  (if when-expr
    (format "(when %s %s)" (second when-expr) expr)
    expr))

(defn wrap-body [task-name prog parallel? log-level]
  (format "(binding [babashka.tasks/*-task-name* \"%s\"
  babashka.tasks/*-log-level* %s]
  (babashka.tasks/-log-info)
  %s)" task-name
          log-level
          (if parallel?
            (format "(future %s)" prog)
            prog)))

(defn wrap-def [task-name prog parallel? last? log-level]
  (format "(def %s %s) %s"
          task-name (wrap-body task-name prog parallel? log-level)
          (if (and parallel? last?)
            (format "(babashka.tasks/-wait %s)" task-name)
            task-name)))

(defn deref-task [dep]
  (format "(def %s (babashka.tasks/-wait %s))" dep dep))

(defn wrap-depends [prog depends parallel?]
  (if parallel?
    (format "(do %s)" (str (str/join "\n" (map deref-task depends)) "\n" prog))
    prog))

(defn assemble-task-1
  "Assembles task, does not process :depends."
  ([task-name task log-level parallel?]
   (assemble-task-1 task-name task log-level parallel? nil nil))
  ([task-name task log-level parallel? last?] (assemble-task-1 task-name task log-level parallel? last? nil))
  ([task-name task log-level parallel? last? depends]
   (let [[task depends] (if (map? task)
                          [(:task task) (:depends task)]
                          [task depends])
         private? (or (:private task)
                      (str/starts-with? task-name "-"))
         log-level (or (:log-level task)
                       (when private?
                         :error)
                       log-level)]
     (if (qualified-symbol? task)
       (let [prog (format "(apply %s *command-line-args*)" task)
             prog (wrap-depends prog depends parallel?)
             prog (wrap-def task-name prog parallel? last? log-level)
             prog (format "
(when-not (resolve '%s) (require (quote %s)))
%s"
                          task
                          (namespace task)
                          prog)]
             prog)
       (let [task (pr-str task)
             prog (wrap-depends task depends parallel?)
             prog (wrap-def task-name prog parallel? last? log-level)]
         prog)))))

(defn format-task [init requires prog]
  (format "
(ns %s %s)
(require '[babashka.tasks])
(when-not (resolve 'clojure)
  ;; we don't use refer so users can override this
  (intern *ns* 'clojure babashka.tasks/clojure))

(when-not (resolve 'shell)
  (intern *ns* 'shell babashka.tasks/shell))

%s
%s"
          (gensym "user")
          (if (seq requires)
            (format "(:require %s)" (str/join " " requires))
            "")
          (str init)
          prog))

(defn target-order
  ([tasks task-name] (target-order tasks task-name (volatile! #{})))
  ([tasks task-name processed]
   (let [task (tasks task-name)
         depends (:depends task)]
     (loop [deps (seq depends)]
       (let [deps (remove #(contains? @processed %) deps)
             order (vec (mapcat #(target-order tasks % processed) deps))]
         (if-not (contains? @processed task-name)
           (do (vswap! processed conj task-name)
               (conj order task-name))
           order))))))

(defn assemble-task [task-name parallel? log-level]
  (let [task-name (symbol task-name)
        bb-edn @bb-edn
        tasks (get bb-edn :tasks)
        log-level (or log-level (:log-level tasks) default-log-level)
        task (get tasks task-name)]
    (if task
      (let [m? (map? task)
            requires (get tasks :requires)
            init (get tasks :init)
            prog (if-let [depends (when m? (:depends task))]
                   (let [targets (target-order tasks task-name)]
                     (loop [prog ""
                            targets (seq targets)
                            requires requires]
                       (let [t (first targets)
                             targets (next targets)]
                         (if targets
                           (if-let [task (get tasks t)]
                             (recur (str prog "\n" (assemble-task-1 t task log-level parallel?))
                                    targets
                                    (concat requires (:requires task)))
                             [(binding [*out* *err*]
                                (println "No such task:" task-name)) 1])
                           (if-let [task (get tasks t)]
                             (let [prog (str prog "\n"
                                             (apply str (map deref-task depends))
                                             "\n"
                                             (assemble-task-1 t task log-level parallel? true))
                                   requires (concat requires (:requires task))]
                               [[(format-task init requires prog)] nil])
                             [(binding [*out* *err*]
                                (println "No such task:" task-name)) 1])))))
                   [[(format-task
                      init
                      (concat requires (:requires task))
                      (assemble-task-1 task-name task log-level parallel? true))] nil])]
        (when (= "true" (System/getenv "BABASHKA_DEV"))
          (.println System/out (ffirst prog)))
        prog)
      [(binding [*out* *err*]
         (println "No such task:" task-name)) 1])))

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
    (filter symbol?
            (map zip/sexpr
                 (take-while #(not (zip/end? %))
                             (take-nth 2 (iterate zip/right loc)))))))

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
                          (str " " d))))))
      (println "No tasks found."))))
