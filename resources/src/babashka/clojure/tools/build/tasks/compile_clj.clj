;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.compile-clj
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.util.file :as file]
    [clojure.tools.build.tasks.process :as process]
    [clojure.tools.namespace.find :as find]
    [clojure.tools.namespace.dependency :as dependency]
    [clojure.tools.namespace.parse :as parse])
  (:import
    [java.io File]
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(defn- write-compile-script!
  ^File [^File script-file ^File compile-dir nses compiler-opts bindings]
  (let [compile-bindings (merge bindings
                           {#'*compile-path* (.toString compile-dir)
                            #'*compiler-options* compiler-opts})
        binding-nses (->> compile-bindings 
                       keys
                       (map #(-> % symbol namespace symbol)) ;; Var->namespace
                       distinct
                       (remove #(= % 'clojure.core)))
        requires (map (fn [n] `(require '~n)) binding-nses)
        do-compile `(with-bindings ~compile-bindings
                      ~@(map (fn [n] `(~'compile '~n)) nses)
                      (System/exit 0))
        script (->> (conj (vec requires) do-compile)
                 (map #(with-out-str (pprint/pprint %)))
                 (str/join (System/lineSeparator)))]
    (spit script-file script)))

(defn- ns->path
  [ns-sym]
  (str/replace (clojure.lang.Compiler/munge (str ns-sym)) \. \/))

(defn- nses-in-bfs
  [dirs]
  (mapcat #(find/find-namespaces-in-dir % find/clj) dirs))

(defn- nses-in-topo
  [dirs]
  (let [ns-decls (mapcat find/find-ns-decls-in-dir dirs)
        ns-candidates (set (map parse/name-from-ns-decl ns-decls))
        graph (reduce
                (fn [graph decl]
                  (let [sym (parse/name-from-ns-decl decl)]
                    (reduce
                      (fn [graph dep] (dependency/depend graph sym dep))
                      graph
                      (parse/deps-from-ns-decl decl))))
                (dependency/graph)
                ns-decls)]
    (->> graph
      dependency/topo-sort
      (filter ns-candidates) ;; only keep stuff in these dirs
      (#(concat % ns-candidates)) ;; but make sure everything is in there at least once
      distinct)))

(defn- basis-paths
  "Extract all path entries from basis, in classpath order"
  [{:keys [classpath classpath-roots]}]
  (let [path-set (->> classpath
                   (filter #(contains? (val %) :path-key))
                   (map key)
                   set)]
    (filter path-set classpath-roots)))

(defn compile-clj
  [{:keys [basis src-dirs compile-opts ns-compile filter-nses class-dir sort bindings] :as params
    :or {sort :topo}}]
  (let [working-dir (.toFile (Files/createTempDirectory "compile-clj" (into-array FileAttribute [])))
        compile-dir-file (file/ensure-dir (api/resolve-path class-dir))
        clj-paths (map api/resolve-path (or src-dirs (basis-paths basis)))
        nses (cond
               (seq ns-compile) ns-compile
               (= sort :topo) (nses-in-topo clj-paths)
               (= sort :bfs) (nses-in-bfs clj-paths)
               :else (throw (ex-info "Missing :ns-compile or :sort order in compile-clj task" {})))
        working-compile-dir (file/ensure-dir (jio/file working-dir "compile-clj"))
        compile-script (jio/file working-dir "compile.clj")
        _ (write-compile-script! compile-script working-compile-dir nses compile-opts bindings)

        ;; java-command will run in context of *project-dir* - basis, classpaths, etc
        ;; should all be relative to that (or absolute like working-compile-dir)
        process-args (merge
                       (process/java-command
                         (merge
                           (select-keys params [:java-cmd :java-opts :use-cp-file])
                           {:cp [(.getPath working-compile-dir) class-dir]
                            :basis basis
                            :main 'clojure.main
                            :main-args [(.getCanonicalPath compile-script)]}))
                       (select-keys params [:out :err :out-file :err-file]))
        _ (spit (jio/file working-dir "compile.args") (str/join " " (:command-args process-args)))
        {exit :exit, ps-out :out, ps-err :err} (process/process process-args)
        ret (cond-> nil
              ps-out (assoc :out ps-out)
              ps-err (assoc :err ps-err))]
    (if (zero? exit)
      (do
        (if (seq filter-nses)
          (file/copy-contents working-compile-dir compile-dir-file (map ns->path filter-nses))
          (file/copy-contents working-compile-dir compile-dir-file))
        ;; only delete on success, otherwise leave the evidence!
        (file/delete working-dir)
        ret)
      (throw (ex-info (str "Clojure compilation failed, working dir preserved: " (.toString working-dir)) ret)))))
