(require '[babashka.process :as proc]
         '[clojure.string :as str])

(def config
  {:skip-if-only [#".*.md$"]})

(defn exec [cmd]
  (-> cmd
      (proc/process)
      (proc/check)))

(defn get-changes []
  (-> "git diff --name-only HEAD~1"
      (exec)
      (:out)
      slurp
      (str/split-lines)))

(defn irrelevant-change? [change regexes]
  (some? (some #(re-matches % change) regexes)))

(defn relevant? [change-set regexes]
  (some? (some  #(not (irrelevant-change? % regexes)) change-set)))

(defn main []
  (let [{:keys [skip-if-only]} config
        changed-files (get-changes)]
    (if (relevant? changed-files skip-if-only)
      (println "Proceeding with CI run")
      (do
        (println "Irrelevant changes - skipping CI run")
        (exec "circleci task halt")))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))

(comment
  (def regexes [#".*.md$"
                #".*.clj"]) ;ignore clojure files
  (irrelevant-change? "src/file.png" regexes)
  (re-matches #".*.clj$" "src/file.clj.dfff")
  (relevant? ["src/file.clj"] regexes))