(ns babashka.impl.tasks
  (:require [babashka.impl.common :refer [bb-edn]]
            [babashka.impl.deps :as deps]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]))

(def sci-ns (sci/create-ns 'babashka.tasks nil))

(defn- exit-non-zero [proc]
  (when-let [exit-code (some-> proc deref :exit)]
    (when (not (zero? exit-code))
      (System/exit exit-code))))

(defn shell [cmd & args]
  (let [[opts cmd args]
        (if (map? cmd)
          [cmd (first args) (rest args)]
          [nil cmd args])
        opts (if-let [o (:out opts)]
               (if (string? o)
                 (update opts :out io/file)
                 opts)
               opts)]
    (exit-non-zero
     (p/process (into (p/tokenize cmd) args)
                (merge {:in :inherit
                        :out :inherit
                        :err :inherit} opts)))))

(defn clojure [cmd & args]
  (let [[opts cmd args]
        (if (map? cmd)
          [cmd (first args) (rest args)]
          [nil cmd args])
        opts (if-let [o (:out opts)]
               (if (string? o)
                 (update opts :out io/file)
                 opts)
               opts)]
    (exit-non-zero
     (deps/clojure (into (p/tokenize cmd) args)
                   (merge {:in :inherit
                           :out :inherit
                           :err :inherit} opts)))))

(defn -wait [res]
  (when res
    (if (future? res)
      @res
      res)))

(def tasks-namespace
  {'shell (sci/copy-var shell sci-ns)
   'clojure (sci/copy-var clojure sci-ns)
   '-wait (sci/copy-var -wait sci-ns)})

(defn depends-map [tasks target-name]
  (let [deps (seq (:depends (get tasks target-name)))
        m [target-name deps]]
    (into {} (cons m (map #(depends-map tasks %) deps)))))

#_(defn wrap-when [expr when-expr]
  (if when-expr
    (format "(when %s %s)" (second when-expr) expr)
    expr))

(defn wrap-def [task-name prog last?]
  (format "(def %s (future %s)) %s"
          task-name prog
          (if last?
            (format "(babashka.tasks/-wait %s)" task-name)
            task-name)))

(defn deref-task [dep]
  (format "(babashka.tasks/-wait %s)" dep))

(defn wrap-depends [prog depends]
  (format "(do %s)" (str (str/join "\n" (map deref-task depends)) "\n" prog)))

(defn assemble-task-1
  "Assembles task, does not process :depends."
  ([task-name task]
   (assemble-task-1 task-name task nil nil))
  ([task-name task last?] (assemble-task-1 task-name task last? nil))
  ([task-name task last? depends]
   (cond (qualified-symbol? task)
         (let [prog (format "(apply %s *command-line-args*)" task)
               prog (wrap-depends prog depends)
               prog (wrap-def task-name prog last?)
               prog (format "
(do (require (quote %s))
%s)"
                            (namespace task)
                            prog)]
           prog)
         (map? task)
         (let [t (:task task)]
           (assemble-task-1 task-name t last? (:depends task)))
         :else (let [prog (wrap-depends task depends)]
                 (wrap-def task-name prog last?)))))

(defn format-task [init prog]
  (format "
(require '[babashka.tasks :refer [shell clojure]])
%s
%s"
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

(defn assemble-task [task-name]
  (let [task-name (symbol task-name)
        tasks (get @bb-edn :tasks)
        task (get tasks task-name)]
    (if task
      (let [m? (map? task)
            init (and m? (get tasks :init))
            prog (if-let [depends (when m? (:depends task))]
                   (let [targets (target-order tasks task-name)]
                     (loop [prog ""
                            targets (seq targets)]
                       (let [t (first targets)
                             targets (next targets)]
                         (if targets
                           (if-let [task (get tasks t)]
                             (recur (str prog "\n" (assemble-task-1 t task))
                                    targets)
                             [(binding [*out* *err*]
                                (println "No such task:" task-name)) 1])
                           (if-let [task (get tasks t)]
                             (let [prog (str prog "\n"
                                             (apply str (map deref-task depends))
                                             "\n"
                                             (assemble-task-1 t task true))]
                               [[(format-task init prog)] nil])
                             [(binding [*out* *err*]
                                (println "No such task:" task-name)) 1])))))
                   [[(format-task init (assemble-task-1 task-name task true))] nil])]
        (when (= "true" (System/getenv "BABASHKA_DEV"))
          (println (ffirst prog)))
        prog)
      [(binding [*out* *err*]
         (println "No such task:" task-name)) 1])))

(defn list-tasks
  []
  (let [tasks (:tasks @bb-edn)]
    (if (seq tasks)
      (let [names (keys tasks)
            names (filter symbol? names)
            names (map str names)
            names (remove #(str/starts-with? % "-") names)
            names (remove #(:private (get tasks (symbol %))) names)
            names (sort names)
            longest (apply max (map count names))
            fmt (str "%1$-" longest "s")]
        (println "The following tasks are available:")
        (println)
        (doseq [k names
                :let [task (get tasks (symbol k))]]
          (let [task (if (qualified-symbol? task)
                       {:doc (format "Runs %s. See `bb doc %s` for more info." task task)}
                       task)]
            (println (str (format fmt k)
                          (when-let [d (:doc task)]
                            (str " " d)))))))
      (println "No tasks found."))))

