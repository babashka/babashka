#!/usr/bin/env bb
;; Runs every *_test.clj in this directory with ./bb and the procurer's
;; sources from the tree, so a change is tested before an image is built.
;; Usage: bb script/mvn_oracle/tests.clj [--image]
;; --image runs the sources bundled in ./bb instead of the tree's.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def image? (some #{"--image"} *command-line-args*))

(def bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb"))))

(def tests (->> (fs/glob "script/mvn_oracle" "*_test.clj")
                (map str)
                sort))

(def results
  (doall
   (for [t tests]
     (let [cmd (cond-> [bb]
                 (not image?) (conj "-cp" "resources/src/babashka")
                 true (conj t))
           {:keys [exit out]} (apply p/shell {:out :string :err :out :continue true
                                              :extra-env {"CLOJURE_CLI_ALLOW_HTTP_REPO" "true"
                                                          "BABASHKA_DEPS_RESOLVER" "bb"}}
                                     cmd)
           summary (or (last (re-seq #"Ran \d+ tests containing \d+ assertions\.\n\d+ failures, \d+ errors\." out))
                       "no summary")]
       (println (format "%-40s %s" (fs/file-name t) (str/replace summary "\n" ", ")))
       (when-not (zero? exit)
         (println out))
       exit))))

(System/exit (if (every? zero? results) 0 1))
