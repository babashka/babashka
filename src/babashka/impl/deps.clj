(ns babashka.impl.deps
  (:require [babashka.deps :as bdeps]
            [babashka.fs :as fs]
            [babashka.impl.classpath :as cp]
            [babashka.impl.common :refer [bb-edn]]
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
  ([deps-map {:keys [:aliases :env :extra-env :force]}]
   (when-let [paths (:paths deps-map)]
     (let [paths (if-let [deps-root (:deps-root @bb-edn)]
                   (let [deps-root (fs/absolutize deps-root)
                         paths (mapv #(str (fs/file deps-root %)) paths)]
                     paths)
                   paths)]
       (cp/add-classpath (str/join cp/path-sep paths))))
   (let [need-deps? (or (seq (:deps deps-map))
                        (and (:aliases deps-map)
                             aliases))]
     (when need-deps?
       (let [deps-map (dissoc deps-map
                              ;; paths are added manually above
                              ;; extra-paths are added as :paths in tasks
                              :paths :tasks :raw :file :deps-root
                              :min-bb-version)]
         (binding [*print-namespace-maps* false]
           (let [deps-map (assoc-in deps-map [:aliases :org.babashka/defaults]
                                    {:replace-paths [] ;; babashka sets paths manually
                                     :classpath-overrides (cond->
                                                              '{org.clojure/clojure ""
                                                                org.clojure/spec.alpha ""}
                                                            ;; only remove core specs when they are not mentioned in deps map
                                                            (not (str/includes? (str deps-map) "org.clojure/core.specs.alpha"))
                                                            (assoc 'org.clojure/core.specs.alpha ""))})
                 args (list "-Srepro" ;; do not include deps.edn from user config
                            "-Spath" "-Sdeps" (str deps-map)
                            "-Sdeps-file" "") ;; we reset deps file so the local deps.edn isn't used
                 args (if force (cons "-Sforce" args) args)
                 args (concat args [(str "-A:" (str/join ":" (cons ":org.babashka/defaults" aliases)))])
                 bindings (cond->
                            {#'deps/*env* env
                             #'deps/*extra-env* extra-env}
                            (:deps-root @bb-edn) (assoc #'deps/*dir* (:deps-root @bb-edn)))
                 cp (with-out-str (with-bindings bindings
                                    (apply deps/-main args)))
                 cp (str/trim cp)
                 cp (str/replace cp (re-pattern (str cp/path-sep "+$")) "")]
             (cp/add-classpath cp))))))))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)
   'clojure (sci/copy-var bdeps/clojure dns)
   'merge-deps (sci/copy-var merge-deps dns)
   ;; undocumented
   'merge-defaults (sci/copy-var merge-default-deps dns {:name 'merge-defaults})})
