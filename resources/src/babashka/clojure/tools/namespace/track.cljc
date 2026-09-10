;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Dependency tracker which can compute which namespaces need to be
  reloaded after files have changed. This is the low-level
  implementation that requires you to find the namespace dependencies
  yourself: most uses will interact with the wrappers in
  clojure.tools.namespace.file and clojure.tools.namespace.dir or the
  public API in clojure.tools.namespace.repl."}
  clojure.tools.namespace.track
  (:refer-clojure :exclude (remove))
  (:require [clojure.set :as set]
            [clojure.tools.namespace.dependency :as dep]))

(defn- remove-deps [deps names]
  (reduce dep/remove-node deps names))

(defn- add-deps [deps depmap]
  (reduce (fn [ds [name dependencies]]
            (reduce (fn [g dep] (dep/depend g name dep))
                    ds dependencies))
          deps depmap))

(defn- update-deps [deps depmap]
  (-> deps
      (remove-deps (keys depmap))
      (add-deps depmap)))

(defn- affected-namespaces [deps names]
  (set/union (set names)
             (dep/transitive-dependents-set deps names)))

(defn add
  "Returns an updated dependency tracker with new/updated namespaces.

  Depmap is a map describing the new or modified namespaces. Keys in
  the map are namespace names (symbols). Values in the map are sets of
  symbols naming the direct dependencies of each namespace. For
  example, assuming these ns declarations:

      (ns alpha (:require beta))
      (ns beta (:require gamma delta))

  the depmap would look like this:

      {alpha #{beta}
       beta  #{gamma delta}}

  After adding new/updated namespaces, the dependency tracker will
  have two lists associated with the following keys:

      :clojure.tools.namespace.track/unload
          is the list of namespaces that need to be removed

      :clojure.tools.namespace.track/load
          is the list of namespaces that need to be reloaded

  To reload namespaces in the correct order, first remove/unload all
  namespaces in the 'unload' list, then (re)load all namespaces in the
  'load' list. The clojure.tools.namespace.reload namespace has
  functions to do this."
  [tracker depmap]
  (let [{load ::load
         unload ::unload
         deps ::deps
         :or {load (), unload (), deps (dep/graph)}} tracker
        new-deps (update-deps deps depmap)
        changed (affected-namespaces new-deps (keys depmap))
        ;; With a new tracker, old dependencies are empty, so
        ;; unload order will be undefined unless we use new
        ;; dependencies (TNS-20).
        old-deps (if (empty? (dep/nodes deps)) new-deps deps)]
    (assoc tracker
      ::deps new-deps
      ::unload (distinct
               (concat (reverse (sort (dep/topo-comparator old-deps) changed))
                       unload))
      ::load (distinct
             (concat (sort (dep/topo-comparator new-deps) changed)
                     load)))))

(defn remove
  "Returns an updated dependency tracker from which the namespaces
  (symbols) have been removed. The ::unload and ::load lists are
  populated as with 'add'."
  [tracker names]
  (let [{load ::load
         unload ::unload
         deps ::deps
         :or {load (), unload (), deps (dep/graph)}} tracker
        known (set (dep/nodes deps))
        removed-names (filter known names)
        new-deps (reduce dep/remove-all deps removed-names)
        changed (affected-namespaces deps removed-names)]
    (assoc tracker
      ::deps new-deps
      ::unload (distinct
                (concat (reverse (sort (dep/topo-comparator deps) changed))
                        unload))
      ::load (distinct
              (filter (complement (set removed-names))
                      (concat (sort (dep/topo-comparator new-deps) changed)
                              load))))))

(defn tracker
  "Returns a new, empty dependency tracker"
  []
  {})

(comment
  ;; Structure of the namespace tracker map. Documented for reference
  ;; only: This is not a public API.

  {;; Dependency graph of namespace names (symbols) as defined in
   ;; clojure.tools.namespace.dependency/graph
   :clojure.tools.namespace.track/deps {}

   ;; Ordered list of namespace names (symbols) that need to be
   ;; removed to bring the running system into agreement with the
   ;; source files.
   :clojure.tools.namespace.track/unload ()

   ;; Ordered list of namespace names (symbols) that need to be
   ;; (re)loaded to bring the running system into agreement with the
   ;; source files.
   :clojure.tools.namespace.track/load ()

   ;; Added by clojure.tools.namespace.file: Map from source files
   ;; (java.io.File) to the names (symbols) of namespaces they
   ;; represent.
   :clojure.tools.namespace.file/filemap {}

   ;; Added by clojure.tools.namespace.dir: Set of source files
   ;; (java.io.File) which have been seen by this dependency tracker;
   ;; used to determine when files have been deleted.
   :clojure.tools.namespace.dir/files #{}

   ;; Added by clojure.tools.namespace.dir: Instant when the
   ;; directories were last scanned, as returned by
   ;; System/currentTimeMillis.
   :clojure.tools.namespace.dir/time 1405201862262})
