#!/usr/bin/env bb
;; Re-sync copied upstream files with a newer revision, by three-way merging
;; each copy against the revision it was taken from. Conflicts are left as
;; markers to resolve by hand, and the recorded revision moves to the new one.
;;
;; Run through the bb task:
;;   bb --config .build/bb.edn --deps-root . resync lib --lib nrepl/nrepl --new-sha <sha>
;;   bb --config .build/bb.edn --deps-root . resync dir --dir script/mvn_oracle/patched --new-sha <sha>
;;
;; lib takes its pin from test-resources/lib_tests/bb-tested-libs.edn and
;; merges the copies under test-resources/lib_tests/<copies>, which defaults
;; to the library's name. dir takes its pin from <dir>/upstream.edn and
;; merges everything under that directory.
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

(defn- checkout-at
  "The gitlibs checkout of `lib` at `sha`, cloned when missing, fetched so
  that any newer revision is available."
  [lib git-url sha]
  (let [dir (fs/file (fs/home) ".gitlibs" "libs" (namespace lib) (name lib) sha)]
    (when-not (fs/exists? dir)
      (println "Fetching" (str lib) "at" sha)
      (shell "git" "clone" "-q" (str git-url) (str dir))
      (shell "git" "-C" (str dir) "checkout" "-q" sha))
    (shell {:dir (str dir)} "git" "fetch" "-q" "origin")
    dir))

(defn- merge-copies
  "Three-way merges every copy under `copies` against `upstream-path` in the
  checkout. `rel-root` is what copy paths are relative to upstream."
  [{:keys [checkout old-sha new-sha copies rel-root upstream-path]}]
  (let [tmp (fs/create-temp-dir)]
    (doall
     (for [f (sort (fs/glob copies "**.{clj,cljc,cljs}"))
           :let [rel (str (fs/relativize rel-root f))
                 path (str upstream-path "/" rel)
                 base (fs/file tmp "base")
                 other (fs/file tmp "other")]]
       (cond
         (not (show checkout old-sha path base))
         [rel :absent-at-old]

         (not (show checkout new-sha path other))
         [rel :absent-at-new]

         :else
         (let [{:keys [exit]} (shell {:continue true :out :string :err :string}
                                     "git" "merge-file" (str f) (str base) (str other))]
           [rel (if (zero? exit) :clean exit)]))))))

(defn- replace-sha
  "Rewrites the first :git-sha after `anchor` in `file`."
  [file anchor old-sha new-sha]
  (let [s (slurp file)
        at (or (str/index-of s anchor) 0)
        from (str/index-of s (str ":git-sha \"" old-sha "\"") at)]
    (when-not from
      (die "Could not find :git-sha" old-sha "in" file))
    (spit file (str (subs s 0 from)
                    ":git-sha \"" new-sha "\""
                    (subs s (+ from (count (str ":git-sha \"" old-sha "\"")))))))) 

(defn- report [results]
  (doseq [[rel status] results]
    (println (format "%-44s %s" rel (case status
                                      :clean "merged cleanly"
                                      :absent-at-old "skipped, not present at the old revision"
                                      :absent-at-new "skipped, gone upstream"
                                      (str status " conflict(s)")))))
  (when (seq (remove (comp #{:clean} second) results))
    (println "Resolve the conflict markers before committing.")))

(defn lib
  "Re-syncs the copies of a library listed in the lib tests registry."
  {:org.babashka/cli {:spec {:lib {:desc "Library, as in the registry, e.g. nrepl/nrepl"
                                   :require true}
                             :new-sha {:desc "Revision to re-sync to"
                                       :require true}
                             :copies {:desc "Directory under test-resources/lib_tests, defaults to the library name"}}}}
  [{:keys [lib new-sha copies]}]
  (let [lib (symbol lib)
        entry (get (edn/read-string (slurp registry)) lib)
        _ (when-not entry (die "No entry for" (str lib) "in" registry))
        {:keys [git-sha git-url test-paths]} entry
        _ (when-not (seq test-paths)
            (die (str lib) "has no :test-paths, so where its copies came from upstream is unknown"))
        copies (fs/file "test-resources/lib_tests" (or copies (name lib)))
        _ (when-not (fs/exists? copies) (die "No copied files at" (str copies)))
        results (merge-copies {:checkout (checkout-at lib git-url git-sha)
                               :old-sha git-sha :new-sha new-sha
                               :copies copies :rel-root (fs/parent copies)
                               :upstream-path (first test-paths)})]
    (report results)
    (replace-sha registry (str lib) git-sha new-sha)
    (println "\n:git-sha in" registry "is now" new-sha)))

(defn dir
  "Re-syncs the copies in a directory that carries its own upstream.edn."
  {:org.babashka/cli {:spec {:dir {:desc "Directory holding the copies and upstream.edn"
                                   :require true}
                             :new-sha {:desc "Revision to re-sync to"
                                       :require true}}}}
  [{:keys [dir new-sha]}]
  (let [descriptor (fs/file dir "upstream.edn")
        _ (when-not (fs/exists? descriptor) (die "No upstream.edn in" dir))
        {:keys [lib git-url git-sha upstream-path]} (edn/read-string (slurp (str descriptor)))
        results (merge-copies {:checkout (checkout-at lib git-url git-sha)
                               :old-sha git-sha :new-sha new-sha
                               :copies (fs/file dir) :rel-root (fs/file dir)
                               :upstream-path upstream-path})]
    (report results)
    (replace-sha (str descriptor) ":git-sha" git-sha new-sha)
    (println "\n:git-sha in" (str descriptor) "is now" new-sha)))
