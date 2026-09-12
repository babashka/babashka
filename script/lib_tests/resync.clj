#!/usr/bin/env bb
;; Re-sync a library's copied tests with a newer upstream revision, by
;; three-way merging each copy against the revision it was taken from.
;; Usage: bb script/lib_tests/resync.clj <lib> <new-sha> [copies-dir]
(ns resync
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell sh]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def registry "test-resources/lib_tests/bb-tested-libs.edn")

(defn- die [& msg]
  (apply println msg)
  (System/exit 1))

(defn- show
  "Writes `path` at `sha` to `dest`. False when the file is absent there."
  [checkout sha path dest]
  (let [{:keys [exit out]} (sh {:dir (str checkout) :continue true}
                               "git" "show" (str sha ":" path))]
    (when (zero? exit)
      (spit dest out)
      true)))

(defn- update-sha
  "Points the entry for `lib` at `new-sha`, leaving the rest of the file alone."
  [lib old-sha new-sha]
  (let [s (slurp registry)
        at (str/index-of s (str lib))
        _ (when-not at (die "No entry for" lib "in" registry))
        from (str/index-of s (str ":git-sha \"" old-sha "\"") at)]
    (when-not from
      (die "Could not find :git-sha" old-sha "for" lib))
    (spit registry (str (subs s 0 from)
                        ":git-sha \"" new-sha "\""
                        (subs s (+ from (count (str ":git-sha \"" old-sha "\""))))))))

(let [[lib new-sha copies-arg] *command-line-args*]
  (when-not (and lib new-sha)
    (die "Usage: bb script/lib_tests/resync.clj <lib> <new-sha> [copies-dir]"))
  (let [lib-sym (symbol lib)
        entry (get (edn/read-string (slurp registry)) lib-sym)
        _ (when-not entry (die "No entry for" lib "in" registry))
        {:keys [git-sha git-url test-paths]} entry
        _ (when-not (seq test-paths)
            (die lib "has no :test-paths, so where its copies came from upstream is unknown"))
        prefix (first test-paths)
        copies (fs/file "test-resources/lib_tests" (or copies-arg (name lib-sym)))
        _ (when-not (fs/exists? copies) (die "No copied tests at" (str copies)))
        checkout (fs/file (fs/home) ".gitlibs" "libs" (namespace lib-sym) (name lib-sym) git-sha)
        _ (when-not (fs/exists? checkout)
            (println "Fetching" lib "at" git-sha)
            (shell "git" "clone" "-q" (str git-url) (str checkout))
            (shell "git" "-C" (str checkout) "checkout" "-q" git-sha))
        _ (shell {:dir (str checkout)} "git" "fetch" "-q" "origin")
        tmp (fs/create-temp-dir)
        results (doall
                 (for [f (sort (fs/glob copies "**.{clj,cljc,cljs}"))
                       :let [rel (str (fs/relativize (fs/parent copies) f))
                             path (str prefix "/" rel)
                             base (fs/file tmp "base")
                             other (fs/file tmp "other")]]
                   (cond
                     (not (show checkout git-sha path base))
                     [rel :absent-at-old]

                     (not (show checkout new-sha path other))
                     [rel :absent-at-new]

                     :else
                     (let [{:keys [exit]} (shell {:continue true :out :string :err :string}
                                                 "git" "merge-file" (str f) (str base) (str other))]
                       [rel (if (zero? exit) :clean exit)]))))
        conflicted (remove (comp #{:clean} second) results)]
    (doseq [[rel status] results]
      (println (format "%-40s %s" rel (case status
                                        :clean "merged cleanly"
                                        :absent-at-old "skipped, not present at the old revision"
                                        :absent-at-new "skipped, gone upstream"
                                        (str status " conflict(s)")))))
    (update-sha lib git-sha new-sha)
    (println "\n:git-sha is now" new-sha)
    (when (seq conflicted)
      (println "Resolve the conflict markers before committing."))))
