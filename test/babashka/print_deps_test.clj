(ns babashka.print-deps-test
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.test-utils :refer [bb]]
            [borkdude.rewrite-edn :as r]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [sci.core :as sci]))


(deftest print-deps-test
  (let [deps (bb nil "print-deps" "--format" "deps")]
    (testing "printed deps map can be read by Clojure"
      (let [tmp-dir (fs/create-temp-dir)]
        (spit (fs/file tmp-dir "deps.edn") deps)
        (let [cp (sci/with-out-str
                   (deps/clojure ["-Spath"] {:dir (str tmp-dir)}))]
          (is (str/includes? cp "babashka.curl")))
        (fs/delete-tree tmp-dir)))

    (testing "keys in dep map are sorted"
      (let [values-sorted? (fn [xs] (= xs (sort xs)))
            deps-edn-str-deps-keys (fn [s]
                                     (->> (r/get (r/parse-string s) :deps)
                                          n/child-sexprs
                                          (partition 2)
                                          (map first)))]
        (is (values-sorted? (deps-edn-str-deps-keys deps)))))))
