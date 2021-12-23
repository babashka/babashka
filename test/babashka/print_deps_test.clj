(ns babashka.print-deps-test
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.test-utils :refer [bb]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]))

(deftest print-deps-test
  (let [deps (bb nil "print-deps" "--format" "deps")
        tmp-dir (fs/create-temp-dir)]
    (testing "printed deps map can be read by Clojure"
      (spit (fs/file tmp-dir "deps.edn") deps)
      (let [cp (sci/with-out-str
                 (deps/clojure ["-Spath"] {:dir (str tmp-dir)}))]
        (is (str/includes? cp "babashka.curl")))
      (fs/delete-tree tmp-dir))))
