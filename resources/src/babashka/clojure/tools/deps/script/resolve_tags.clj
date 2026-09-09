;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.script.resolve-tags
  "Implements the Clojure CLI tag resolver.
  IMPL namespace, subject to change without warning.
  Likely to move to CLI code base in the future."
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :as pp]
    [clojure.walk :as walk]
    [clojure.tools.deps.edn :as depsedn]
    [clojure.tools.deps.extensions.git :as git]
    [clojure.tools.deps.util.io :refer [printerrln]]
    [clojure.tools.gitlibs :as gitlibs]
    [clojure.tools.cli :as cli])
  (:import
    [clojure.lang IExceptionInfo]))

(def ^:private opts
  [[nil "--deps-file PATH" "deps.edn file to update"]])

(defn- resolve-git-dep
  [counter lib {untag :tag, unsha :sha, :git/keys [url tag sha] :as git-coord}]
  (let [the-url (or url (git/auto-git-url lib))
        the-tag (or tag untag)
        the-sha (or sha unsha)]
    (if the-tag
      (let [new-sha (gitlibs/resolve the-url the-tag)]
        (if (= the-sha new-sha)
          git-coord ;; no change
          (do
            (printerrln "Resolved" the-tag "=>" new-sha "in" the-url)
            (swap! counter inc)
            (cond-> {:git/tag the-tag
                     :git/sha (subs new-sha 0 7)}
              url (assoc :git/url url)))))
      git-coord)))

(defn- resolve-git-deps
  [counter deps-map]
  (let [f (partial resolve-git-dep counter)]
    (walk/postwalk
      (fn [node]
        (if (map? node)
          (reduce-kv
            (fn [new-deps k v]
              (if (and (symbol? k) (map? v)
                    (contains? (->> v keys (map namespace) set) "git"))
                (assoc new-deps k (resolve-git-dep counter k v))
                new-deps))
            node node)
          node))
      deps-map)))

(defn exec
  [{:keys [deps-file]}]
  (try
    (let [deps-map (depsedn/read-deps (jio/file deps-file))
          counter (atom 0)]
      (printerrln "Resolving git tags in" deps-file "...")
      (let [resolved-map (resolve-git-deps counter deps-map)]
        (if (zero? @counter)
          (printerrln "No unresolved tags found.")
          (spit deps-file
            (with-out-str
              (with-bindings {#'pp/*print-right-margin* 100
                              #'pp/*print-miser-width* 80
                              #'*print-namespace-maps* false}
                (pp/pprint resolved-map)))))))
    (catch Throwable t
      (printerrln "Error resolving tags." (.getMessage t))
      (when-not (instance? IExceptionInfo t)
        (.printStackTrace t))
      (System/exit 1))))

(defn -main
  "Main entry point for resolve-tags script.

  Required:
    --deps-file deps.edn - deps.edn files in which to resolve git tags

  Read deps.edn, find git coordinates with :tag but without :sha, resolve those
  tags to shas, and over-write the deps.edn."
  [& args]
  (let [{:keys [options]} (cli/parse-opts args opts)]
    (exec options)))

(comment
  (-main "--deps-file" "deps.edn")
  )
