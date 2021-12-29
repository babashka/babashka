(ns nbb-runner
  (:require [clojure.string :as str]
            [clojure.test :refer [run-tests]]
            [nbb.classpath :as cp]))

(cp/add-classpath (str/join ":" ["src/cljs" "src/cljc" "test"]))

(require '[markdown.md-test])

(run-tests 'markdown.md-test)
