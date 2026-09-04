#!/usr/bin/env bb
;; Generate patched spec.alpha sources for the reachability bisection.
;; Each variant is a directory with full replacement files.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def jar (str (fs/expand-home "~/.m2/repository/org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar")))
(def out (fs/path "poc-logs" "spec-variants"))

(defn upstream [path]
  (:out (p/shell {:out :string} "unzip" "-p" jar path)))

(def dynaload-old
  "(defn- dynaload\n  [s]\n  (let [ns (namespace s)]\n    (assert ns)\n    (locking dynalock\n      (require (c/symbol ns)))\n    (let [v (resolve s)]\n      (if v\n        @v\n        (throw (RuntimeException. (str \"Var \" s \" is not on the classpath\")))))))")
(def dynaload-new
  "(defn- dynaload\n  [s]\n  (throw (RuntimeException. (str \"dynaload stubbed for the experiment: \" s))))")

(def exercise-old "(let [f (if (symbol? sym-or-f) (resolve sym-or-f) sym-or-f)]")
(def exercise-new "(let [f sym-or-f]")

(def res-old "(symbol? form) (c/or (-> form resolve ->sym) form)")
(def res-new "(symbol? form) form")

;; resolve without the-ns/find-ns: straight into the compiler's lookup, which
;; still calls RT.classForName for dotted symbols.
(def res-maybe "(symbol? form) (c/or (some-> (clojure.lang.Compiler/maybeResolveIn *ns* form) ->sym) form)")

;; maybeResolveIn without its RT.classForName branch: var lookup only.
(def res-novars "(symbol? form) (c/or (some-> (if (namespace form) (some-> (find-ns (c/symbol (namespace form))) (.findInternedVar (c/symbol (name form)))) (.getMapping *ns* form)) ->sym) form)")

;; Only the namespace registry read.
(def res-find-ns "(symbol? form) (c/or (some-> (find-ns (c/symbol (c/or (namespace form) (name form)))) ->sym) form)")
;; Only a mapping read on the current namespace.
(def res-mapping "(symbol? form) (c/or (some-> (.getMapping ^clojure.lang.Namespace *ns* form) ->sym) form)")

(defn patch [src pairs]
  (reduce (fn [s [old new]]
            (assert (str/includes? s old) (str "not found: " (subs old 0 30)))
            (str/replace s old new))
          src pairs))

(def variants
  {"C" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
        "clojure/spec/alpha.clj" [[exercise-old exercise-new]]}
   "D" {"clojure/spec/alpha.clj" [[exercise-old exercise-new]]}
   "E" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
        "clojure/spec/alpha.clj" [[exercise-old exercise-new] [res-old res-new]]}
   "G" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
        "clojure/spec/alpha.clj" [[exercise-old exercise-new] [res-old res-maybe]]}
   "H" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
        "clojure/spec/alpha.clj" [[exercise-old exercise-new] [res-old res-novars]]}
   "I1" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
         "clojure/spec/alpha.clj" [[exercise-old exercise-new] [res-old res-find-ns]]}
   "I2" {"clojure/spec/gen/alpha.clj" [[dynaload-old dynaload-new]]
         "clojure/spec/alpha.clj" [[exercise-old exercise-new] [res-old res-mapping]]}})

(doseq [[name files] variants
        [path pairs] files]
  (let [dest (fs/path out name path)]
    (fs/create-dirs (fs/parent dest))
    (spit (str dest) (patch (upstream path) pairs))
    (println name path)))
