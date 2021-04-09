(ns babashka.impl.tasks
  (:require [babashka.fs :as fs]
            [babashka.impl.common :refer [ctx bb-edn]]
            [babashka.impl.deps :as deps]
            [babashka.process :as p]
            [sci.core :as sci]
            [clojure.java.io :as io]))

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
     (p/process (into (p/tokenize cmd) args) (merge {:in :inherit
                                                     :out :inherit
                                                     :err :inherit} opts)))))


(defn last-modified-1
  "Returns max last-modified of regular file f. Returns 0 if file does not exist."
  [f]
  (if (fs/exists? f)
    (fs/file-time->millis
     (fs/last-modified-time f))
    0))

(defn last-modified
  "Returns max last-modified of f or of all files within f"
  [f]
  (if (fs/exists? f)
    (if (fs/regular-file? f)
      (last-modified-1 f)
      (apply max 0
             (map last-modified-1
                  (filter fs/regular-file? (file-seq (fs/file f))))))
    0))

(defn clojure [cmd & args]
  (exit-non-zero (deps/clojure (into (p/tokenize cmd) args))))

(defn run [task]
  (let [task (get-in @bb-edn [:tasks task])]
    (when task
      (sci/eval-form @ctx task))))

(defn expand-file-set
  [file-set]
  (if (coll? file-set)
    (mapcat expand-file-set file-set)
    (filter fs/regular-file? (file-seq (fs/file file-set)))))

(defn modified-since [anchor file-set]
  (let [lm (last-modified anchor)]
    (seq (map str (filter #(> (last-modified-1 %) lm) (expand-file-set file-set))))))

(def tasks-namespace
  {'shell (sci/copy-var shell sci-ns)
   'clojure (sci/copy-var clojure sci-ns)
   'run (sci/copy-var run sci-ns)
   'modified-since (sci/copy-var modified-since sci-ns)})

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
(require '[babashka.tasks :refer [shell clojure run modified-since]])
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
       (let [p @processed
             deps (remove #(contains? p %) deps)
             order (vec (mapcat #(target-order tasks % processed) deps))]
         (vswap! processed conj task-name)
         (conj order task-name))))))

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