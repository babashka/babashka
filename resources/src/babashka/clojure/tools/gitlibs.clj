;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.gitlibs
  "An API for retrieving and caching git repos and working trees.

  The git url can be either an https url for anonymous checkout or an ssh url
  for private access. revs can be either full sha, prefix sha, or tag name."
  (:refer-clojure :exclude [resolve])
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.gitlibs.config :as config]
    [clojure.tools.gitlibs.impl :as impl]))

(set! *warn-on-reflection* true)

(defn cache-dir
  "Return the root gitlibs cache directory. By default ~/.gitlibs or
  override by setting the environment variable GITLIBS."
  []
  (:gitlibs/dir @config/CONFIG))

;; Possible new API, internal for now
(defn- resolve-all
  "Takes a git url and a coll of revs, and returns the full commit shas.
  Each rev may be a partial sha, full sha, or tag name. Returns nil for
  unresolveable revs."
  [url revs]
  (let [git-dir (impl/ensure-git-dir url)]
    (reduce
      (fn [rs r]
        (if-let [res (impl/git-rev-parse git-dir r)]
          (conj rs res)
          (do ;; could not resolve - fetch and try again
            (impl/git-fetch (jio/file git-dir))
            (conj rs (impl/git-rev-parse git-dir r)))))
      [] revs)))

(defn resolve
  "Takes a git url and a rev, and returns the full commit sha or nil if can't
  resolve. rev may be a partial sha, full sha, or tag name."
  [url rev]
  (first (resolve-all url [rev])))

(defn object-type
  "Takes a git url and rev, and returns the object type, one of :tag :tree
  :commit or :blob, or nil if not known or ambiguous."
  [url rev]
  (let [git-dir (impl/ensure-git-dir url)]
    (if-let [type (impl/git-type git-dir rev)]
      type
      (do
        (impl/git-fetch (jio/file git-dir))
        (impl/git-type git-dir rev)))))

(defn procure
  "Procure a working tree at rev for the git url representing the library lib,
  returns the directory path. lib is a qualified symbol where the qualifier is a
  controlled or conveyed identity, or nil if rev is unknown."
  [url lib rev]
  (let [lib-dir (impl/lib-dir lib)
        git-dir-path (impl/ensure-git-dir url)
        sha (or (impl/match-exact lib-dir rev) (impl/match-prefix lib-dir rev) (resolve url rev))]
    (when sha
      (let [sha-dir (jio/file lib-dir sha)]
        (when-not (.exists sha-dir)
          (impl/printerrln "Checking out:" url "at" rev)
          (impl/git-checkout git-dir-path lib-dir sha))
        (.getCanonicalPath sha-dir)))))

(defn descendant
  "Returns rev in git url which is a descendant of all other revs,
  or nil if no such relationship can be established."
  [url revs]
  (when (seq revs)
    (let [shas (resolve-all url revs)]
      (if (seq (filter nil? shas))
        nil ;; can't resolve all shas in this repo
        (let [git-dir (impl/ensure-git-dir url)]
          (->> shas (sort (partial impl/commit-comparator git-dir)) first))))))

(defn tags
  "Fetches, then returns coll of tags in git url, nil if none"
  [url]
  (impl/tags (impl/ensure-git-dir url)))

(defn commit-sha
  "Returns unpeeled full commit sha, given a rev (which may be tag, branch, etc)"
  [url rev]
  (impl/git-rev-parse (impl/ensure-git-dir url) rev))

(comment
  (commit-sha "https://github.com/clojure/tools.build.git" "v0.8.2")

  (System/setProperty "clojure.gitlibs.debug" "true")
  (resolve "git@github.com:clojure/tools.gitlibs.git" "11fc774")
  (descendant "https://github.com/clojure/tools.gitlibs.git" ["5e2797a487c" "11fc774" "d82adc29" "815e312310"])

  (println
    @(future (procure "https://github.com/clojure/tools.gitlibs.git" 'org.clojure/tools.gitlibs "11fc77496f013871c8af3514bbba03de0af28061"))
    @(future (procure "https://github.com/clojure/tools.gitlibs.git" 'org.clojure/tools.gitlibs "11fc77496f013871c8af3514bbba03de0af28061")))

  (println
    @(future (procure "https://github.com/cognitect-labs/test-runner.git" 'cognitect-labs/test-runner "b6b3193fcc42659d7e46ecd1884a228993441182"))
    @(future (procure "https://github.com/cognitect-labs/test-runner.git" 'cognitect-labs/test-runner "cb96e80f6f3d3b307c59cbeb49bb0dcb3a2a780b"))
    @(future (procure "https://github.com/cognitect-labs/test-runner.git" 'cognitect-labs/test-runner "9e1098965f2089c8cf492d23c0b7520f8690440a")))

  (tags "https://github.com/clojure/tools.gitlibs.git")

  ;; big output
  (tags "https://github.com/confluentinc/kafka-streams-examples.git")

  ;; no tags
  (tags "https://github.com/clojure/clojure-site.git")
  (clojure.repl/pst *e)
  )
