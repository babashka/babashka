;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Bidirectional graphs of dependencies and dependent objects."}
  clojure.tools.namespace.dependency
  (:require [clojure.set :as set]))

(defprotocol DependencyGraph
  (immediate-dependencies [graph node]
    "Returns the set of immediate dependencies of node.")
  (immediate-dependents [graph node]
    "Returns the set of immediate dependents of node.")
  (transitive-dependencies [graph node]
    "Returns the set of all things which node depends on, directly or
    transitively.")
  (transitive-dependencies-set [graph node-set]
    "Returns the set of all things which any node in node-set depends
    on, directly or transitively.")
  (transitive-dependents [graph node]
    "Returns the set of all things which depend upon node, directly or
    transitively.")
  (transitive-dependents-set [graph node-set]
    "Returns the set of all things which depend upon any node in
    node-set, directly or transitively.")
  (nodes [graph]
    "Returns the set of all nodes in graph."))

(defprotocol DependencyGraphUpdate
  (depend [graph node dep]
    "Returns a new graph with a dependency from node to dep (\"node depends
    on dep\"). Forbids circular dependencies.")
  (remove-edge [graph node dep]
    "Returns a new graph with the dependency from node to dep removed.")
  (remove-all [graph node]
    "Returns a new dependency graph with all references to node removed.")
  (remove-node [graph node]
    "Removes the node from the dependency graph without removing it as a
    dependency of other nodes. That is, removes all outgoing edges from
    node."))

(defn- remove-from-map [amap x]
  (reduce (fn [m [k vs]]
	    (assoc m k (disj vs x)))
	  {} (dissoc amap x)))

(defn- transitive
  "Recursively expands the set of dependency relationships starting
  at (get neighbors x), for each x in node-set"
  [neighbors node-set]
  (loop [unexpanded (mapcat neighbors node-set)
         expanded #{}]
    (if-let [[node & more] (seq unexpanded)]
      (if (contains? expanded node)
        (recur more expanded)
        (recur (concat more (neighbors node))
               (conj expanded node)))
      expanded)))

(declare depends?)

(def set-conj (fnil conj #{}))

(defrecord MapDependencyGraph [dependencies dependents]
  DependencyGraph
  (immediate-dependencies [graph node]
    (get dependencies node #{}))
  (immediate-dependents [graph node]
    (get dependents node #{}))
  (transitive-dependencies [graph node]
    (transitive dependencies #{node}))
  (transitive-dependencies-set [graph node-set]
    (transitive dependencies node-set))
  (transitive-dependents [graph node]
    (transitive dependents #{node}))
  (transitive-dependents-set [graph node-set]
    (transitive dependents node-set))
  (nodes [graph]
    (clojure.set/union (set (keys dependencies))
                       (set (keys dependents))))
  DependencyGraphUpdate
  (depend [graph node dep]
    (when (or (= node dep) (depends? graph dep node))
      (throw (ex-info (str "Circular dependency between "
                           (pr-str node) " and " (pr-str dep))
                      {:reason ::circular-dependency
                       :node node
                       :dependency dep})))
    (MapDependencyGraph.
     (update-in dependencies [node] set-conj dep)
     (update-in dependents [dep] set-conj node)))
  (remove-edge [graph node dep]
    (MapDependencyGraph.
     (update-in dependencies [node] disj dep)
     (update-in dependents [dep] disj node)))
  (remove-all [graph node]
    (MapDependencyGraph.
     (remove-from-map dependencies node)
     (remove-from-map dependents node)))
  (remove-node [graph node]
    (MapDependencyGraph.
     (dissoc dependencies node)
     dependents)))

(defn graph "Returns a new, empty, dependency graph." []
  (->MapDependencyGraph {} {}))

(defn depends?
  "True if x is directly or transitively dependent on y."
  [graph x y]
  (contains? (transitive-dependencies graph x) y))

(defn dependent?
  "True if y is a dependent of x."
  [graph x y]
  (contains? (transitive-dependents graph x) y))

(defn topo-sort
  "Returns a topologically-sorted list of nodes in graph."
  [graph]
  (loop [sorted ()
         g graph
         todo (set (filter #(empty? (immediate-dependents graph %))
                           (nodes graph)))]
    (if (empty? todo)
      sorted
      (let [[node & more] (seq todo)
            deps (immediate-dependencies g node)
            [add g'] (loop [deps deps
                            g g
                            add #{}]
                       (if (seq deps)
                         (let [d (first deps)
                               g' (remove-edge g node d)]
                           (if (empty? (immediate-dependents g' d))
                             (recur (rest deps) g' (conj add d))
                             (recur (rest deps) g' add)))
                         [add g]))]
        (recur (cons node sorted)
               (remove-node g' node)
               (clojure.set/union (set more) (set add)))))))

(def ^:private max-number
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_VALUE))

(defn topo-comparator
  "Returns a comparator fn which produces a topological sort based on
  the dependencies in graph. Nodes not present in the graph will sort
  after nodes in the graph."
  [graph]
  (let [pos (zipmap (topo-sort graph) (range))]
    (fn [a b]
      (compare (get pos a max-number)
               (get pos b max-number)))))
