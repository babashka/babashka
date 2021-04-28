(ns babashka.impl.deps
  (:require [babashka.impl.classpath :as cp]
            [babashka.process :as p]
            [borkdude.deps :as deps]
            [clojure.string :as str]
            [sci.core :as sci]))

(def dns (sci/create-ns 'babashka.deps nil))

;;;; merge deps.edn files

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
            nil vals)))

(defn merge-deps
  "Merge multiple deps edn maps from left to right into a single deps edn map."
  [deps-edn-maps]
  (apply merge-with merge-or-replace (remove nil? deps-edn-maps)))

(defn- merge-defaults [deps defaults]
  (let [overriden (select-keys deps (keys defaults))
        overriden-deps (keys overriden)
        defaults (select-keys defaults overriden-deps)]
    (merge deps defaults)))

(defn merge-default-deps [deps-map defaults]
  (let [paths (into [[:deps]]
                    (map (fn [alias]
                           [:aliases alias])
                         (keys (:aliases deps-map))))]
    (reduce
     (fn [acc path]
       (update-in acc path merge-defaults defaults))
     deps-map
     paths)))

#_(merge-default-deps '{:deps {medley/medley nil}
                      :aliases {:foo {medley/medley nil}}}
                    '{medley/medley {:mvn/version "1.3.0"}})

;;;; end merge edn files

;; We are optimizing for the 1-file script with deps scenario where people can
;; call this function to include e.g. {:deps {medley/medley
;; {:mvn/version "1.3.3"}}}. Optionally they can include aliases, to modify the
;; classpath.
(defn add-deps
  "Takes deps edn map and optionally a map with :aliases (seq of
  keywords) which will used to calculate classpath. The classpath is
  then used to resolve dependencies in babashka."
  ([deps-map] (add-deps deps-map nil))
  ([deps-map {:keys [:aliases]}]
   (when-let [paths (:paths deps-map)]
     (cp/add-classpath (str/join cp/path-sep paths)))
   (when-let [deps-map (not-empty (dissoc deps-map :paths :tasks :raw))]
     (let [deps-map (assoc-in deps-map [:aliases :org.babashka/defaults]
                              '{:replace-paths [] ;; babashka sets paths manually
                                :classpath-overrides {org.clojure/clojure ""
                                                      org.clojure/spec.alpha ""
                                                      org.clojure/core.specs.alpha ""}})
           args ["-Srepro" ;; do not include deps.edn from user config
                 "-Spath" "-Sdeps" (str deps-map)
                 "-Sdeps-file" "" ;; we reset deps file so the local deps.edn isn't used
                 ,]
           args (conj args (str "-A:" (str/join ":" (cons ":org.babashka/defaults" aliases))))
           cp (with-out-str (apply deps/-main args))
           cp (str/trim cp)
           cp (str/replace cp (re-pattern (str cp/path-sep "+$")) "")]
       (cp/add-classpath cp)))))

(defn clojure
  "Starts clojure similar to CLI. Use `rlwrap bb` for `clj`-like invocation.
  Invokes java with babashka.process/process for `-M`, `-X` and `-A`
  and returns the associated record. Default options passed to
  babashka.process/process are:

  {:in  :inherit
   :out :inherit
   :err :inherit
   :shutdown p/destroy-tree}

  which can be overriden with opts.

  Returns `nil` and prints to *out* for --help, -Spath, -Sdescribe and
  -Stree.

  Examples:

  (-> (clojure '[-M -e (+ 1 2 3)] {:out :string}) deref :out) returns
  \"6\n\".

  (-> @(clojure) :exit) starts a clojure REPL, waits for it
  to finish and returns the exit code from the process."
  ([] (clojure []))
  ([args] (clojure args nil))
  ([args opts]
   (let [opts (merge {:in  :inherit
                      :out :inherit
                      :err :inherit
                      :shutdown p/destroy-tree}
                     opts)]
     (binding [*in* @sci/in
               *out* @sci/out
               *err* @sci/err
               deps/*process-fn* (fn
                                   ([cmd] (p/process cmd opts))
                                   ([cmd _] (p/process cmd opts)))
               deps/*exit-fn* (fn
                                ([_])
                                ([_exit-code msg]
                                 (throw (Exception. msg))))]
       (apply deps/-main (map str args))))))

;; (-> (clojure ["-Sdeps" edn "-M:foo"] {:out :inherit}) p/check)

;; TODO:
;; (uberjar {:out "final.jar" :main 'foo.bar})
;; (uberscript {:out "final.clj" :main 'foo.bar})

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)
   'clojure (sci/copy-var clojure dns)
   'merge-deps (sci/copy-var merge-deps dns)
   ;; undocumented
   'merge-defaults (sci/copy-var merge-default-deps dns)})
