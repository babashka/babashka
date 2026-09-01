;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.tree
  "API for managing deps trace structures and extracting tree renderings from
  traces. Note that deps are actually a graph and trees usually show the library
  more than once, expanding only once."
  (:require
    [clojure.walk :as walk]
    [clojure.tools.deps :as deps]
    [clojure.tools.deps.extensions :as ext]))

;; Data manipulation

(defn trace->tree
  "Convert a deps trace data structure into a deps tree.

  A deps tree has the structure of the full dependency expansion.
  Each node of the tree is a map from lib to coord-info with at least these keys:
    :lib - library symbol
    :coord - the coord map that was used (may not be the original coord if replaced
             due to default-deps or override-deps)
    :include - boolean of whether this node is included in the returned deps
    :reason - why the node was or was not included
    :children - vector of child nodes"
  [trace]
  (let [{:keys [log]} trace]
    (loop [[{:keys [lib path reason] :as step} & steps] log
           i 0
           tree {}]
      (if step
        (let [nstep (assoc step :step i)
              full-path (conj path lib)
              tree-path (interleave (repeat :children) full-path)
              tree' (if (= reason :newer-version)
                      (walk/postwalk
                        (fn [v]
                          (if (and (map? v) (contains? v lib) (-> v lib :include))
                            (assoc v lib (merge (get v lib) {:include false, :reason :superseded}))
                            v))
                        tree)
                      tree)]
          (recur steps (inc i) (assoc-in tree' tree-path nstep)))
        tree))))

(defn calc-trace
  "Like calc-basis, but create and return the dep expansion trace. The trace
  can be passed to trace->tree to get tree data.

  The opts map includes the same opts accepted by clojure.tools.deps/create-basis.
  By default, uses the   root, user, and project deps and no argmaps (essentially the same
  classpath you get by default from the Clojure CLI).

  Each dep source value can be :standard, a string path, a deps edn map, or nil.
  Sources are merged in the order - :root, :user, :project, :extra.

  Aliases refer to argmaps in the merged deps that will be supplied to the basis
  subprocesses (tool, resolve-deps, make-classpath-map).

  Options:
    :dir     - directory root path, defaults to current directory
    :root    - dep source, default = :standard
    :user    - dep source, default = :standard
    :project - dep source, default = :standard (\"./deps.edn\")
    :extra   - dep source, default = nil
    :aliases - coll of aliases of argmaps to apply to subprocesses"
  ([] (calc-trace nil))
  ([opts]
   (let [{:keys [extra aliases]} opts
         trace-opts (merge opts
                      {:extra (assoc-in extra [:aliases :__TRACE__ :trace] true)}
                      {:aliases (conj (or aliases []) :__TRACE__)})]

     (-> trace-opts deps/create-basis :libs meta :trace))))

;; Printing

(defn- space
  [n]
  (apply str (repeat n \space)))

(defn- print-node
  [{:keys [lib coord include reason]} indented {:keys [hide-libs]}]
  (when (and lib (or (= reason :new-top-dep) (not (contains? hide-libs lib))))
    (let [pre (space indented)
          summary (ext/coord-summary lib coord)]
      (println
        (case reason
          :new-top-dep
          (str pre summary)

          (:new-dep :same-version)
          (str pre ". " summary)

          :newer-version
          (str pre ". " summary " " reason)

          (:use-top :older-version :excluded :parent-omitted :superseded) ;; :superseded is internal here
          (str pre "X " summary " " reason)

          ;; fallthrough, unknown reason
          (str pre "? " summary include reason))))))

(defn print-tree
  "Print the tree to the console.
   Options:
     :indent    Indent spacing (default = 2)
     :hide-libs Set of libs to ignore as deps under top deps, default = #{org.clojure/clojure}"
  ([tree {:keys [indent] :or {indent 2} :as opts}]
   (print-tree tree (- 0 indent) opts))
  ([{:keys [children] :as tree} indented opts]
   (let [opts' (merge {:indent 2, :hide-libs '#{org.clojure/clojure}} opts)]
     (print-node tree indented opts')
     (doseq [child (sort-by :step (vals children))]
       (print-tree child (+ indented (:indent opts')) opts')))))

(comment
  (require '[clojure.tools.deps.util.io :as io])
  (-> "/Users/alex.miller/tmp/20201124/trace.edn" io/slurp-edn trace->tree (print-tree {}))
  (-> "/Users/alex.miller/code/tools.deps/trace.edn" io/slurp-edn trace->tree (print-tree {}))
  (-> "/Users/alex.miller/tmp/20201022/trace.edn" io/slurp-edn trace->tree (print-tree {}))
  (let [log (:log (io/slurp-edn "/Users/alex.miller/tmp/20201124/trace.edn"))]
    (distinct (map :reason log)))

  )