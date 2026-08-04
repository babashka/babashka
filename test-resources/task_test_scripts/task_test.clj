(ns task-test)

(defn task-test-fn []
  :task-test-fn)

;; A dep handler reachable only through its own task's :extra-paths, so
;; completion has to put those on the classpath like a run does.
(defn dep-on-extra-path
  {:org.babashka/cli {:spec {:on-extra-path {:desc "from the dep's extra-paths"}}}}
  [opts]
  (prn (assoc opts :ran :dep-on-extra-path)))
