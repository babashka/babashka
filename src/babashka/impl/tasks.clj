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

(def tasks-namespace
  {'shell (sci/copy-var shell sci-ns)
   'clojure (sci/copy-var clojure sci-ns)})

(defn depends-map [tasks target-name]
  (let [deps (seq (:depends (get tasks target-name)))
        m [target-name deps]]
    (into {} (cons m (map #(depends-map tasks %) deps)))))

(defn assemble-task-1
  "Assembles task, does not process :depends."
  [task]
  (cond (qualified-symbol? task)
        (format "
(do (require (quote %s))
(apply %s *command-line-args*))"
                (namespace task)
                task)
        (map? task)
        (let [task (:task task)]
          (assemble-task-1 task))
        :else task))

(defn format-task [init when-expr prog]
  (format "
(require '[babashka.tasks :refer [shell clojure]])
%s
%s"
          (str init)
          (if when-expr
            (format "(when %s %s)"
                    when-expr prog)
            prog)))

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
      (let [init (get tasks :init)
            when-expr (get task :when)
            prog (if (:depends task)
                   (let [targets (target-order tasks task-name)]
                     (loop [prog ""
                            targets (seq targets)]
                       (if-let [t (first targets)]
                         (if-let [task (get tasks t)]
                           (recur (str prog "\n" (assemble-task-1 task))
                                  (next targets))
                           [(binding [*out* *err*]
                              (println "No such task:" task-name)) 1])
                         [[(format-task init when-expr prog)] nil])))
                   [[(format-task init when-expr (assemble-task-1 task))] nil])]
        prog)
      [(binding [*out* *err*]
         (println "No such task:" task-name)) 1])))

(defn list-tasks
  []
  (let [tasks (:tasks @bb-edn)]
    (if (seq tasks)
      (let [names (keys tasks)
            raw-names (filter symbol? names)
            names (map str raw-names)
            names (sort names)
            longest (apply max (map count names))
            fmt (str "%1$-" longest "s")]
        (println "The following tasks are available:")
        (println)
        (doseq [k raw-names
                :let [task (get tasks k)]]
          (when-not (or (str/starts-with? k "-")
                        (:private task))
            (let [task (if (qualified-symbol? task)
                         {:doc (format "Runs %s. See `bb doc %s` for more info." task task)}
                         task)]
              (println (str (format fmt k)
                            (when-let [d (:doc task)]
                              (str " " d))))))))
      (println "No tasks found."))))

