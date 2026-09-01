#!/usr/bin/env bb
(ns mvn-oracle.run
  "Resolves corpus entries with ./bb and with the JVM tools.deps and diffs
  the results. Usage: bb script/mvn_oracle/run.clj [--cold] [entry ...]
  --cold wipes the shared local repo first, so bb downloads and the JVM
  then has to accept what bb wrote."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data :as data]
            [clojure.edn :as edn]))

(def dir "script/mvn_oracle")
(def corpus-file (str dir "/corpus.edn"))
(def out-dir "target/mvn-oracle")
(def local-repo (str (fs/absolutize (str out-dir "/m2"))))

(def jvm-deps
  {:aliases {:o {:extra-paths ["script"]
                 :extra-deps {'org.clojure/tools.deps
                              {:mvn/version "0.31.1638"
                               :exclusions ['org.clojure/tools.deps.maven-s3-transporter]}}}}})

(defn- run-side [entry side]
  (let [out (str out-dir "/" entry "." (name side) ".edn")
        res (case side
              :bb (p/shell {:out :string :err :inherit :continue true}
                           "./bb" (str dir "/oracle.clj") corpus-file entry local-repo)
              :jvm (p/shell {:out :string :err :inherit :continue true}
                            "clojure" "-Sdeps" (pr-str jvm-deps) "-M:o" "-m" "mvn-oracle.oracle"
                            corpus-file entry local-repo))]
    (spit out (:out res))
    (if (zero? (:exit res))
      (edn/read-string (:out res))
      {:error (:exit res)})))

(defn- report-diff [label a b]
  (let [[only-a only-b _] (data/diff a b)]
    (when (or only-a only-b)
      (println "  " label)
      (doseq [k (sort (distinct (concat (keys only-a) (keys only-b))))]
        (println "    " k)
        (println "       bb :" (pr-str (get only-a k)))
        (println "       jvm:" (pr-str (get only-b k))))
      true)))

(let [args *command-line-args*
      cold? (some #{"--cold"} args)
      names (remove #{"--cold"} args)
      corpus (edn/read-string (slurp corpus-file))
      names (if (seq names) names (map :name corpus))]
  (when cold? (fs/delete-tree local-repo))
  (fs/create-dirs local-repo)
  (let [failed (atom [])]
    (doseq [entry names]
      (println "==" entry)
      (let [bb (run-side entry :bb)
            jvm (run-side entry :jvm)]
        (cond
          (:error bb) (do (println "   bb failed, exit" (:error bb)) (swap! failed conj entry))
          (:error jvm) (do (println "   jvm failed, exit" (:error jvm)) (swap! failed conj entry))
          :else
          (let [d1 (report-diff ":libs" (:libs bb) (:libs jvm))
                d2 (report-diff ":coord-deps" (:coord-deps bb) (:coord-deps jvm))]
            (if (or d1 d2)
              (swap! failed conj entry)
              (println "   ok," (count (:libs bb)) "libs"))))))
    (println)
    (if (seq @failed)
      (do (println "differ:" (pr-str @failed)) (System/exit 1))
      (println "all entries match"))))
