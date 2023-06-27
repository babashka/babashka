(ns babashka.print-deps-test
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.test-utils :refer [bb]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]))

(deftest print-deps-test
  (let [deps (bb nil "print-deps" "--format" "deps")]
    (let [tmp-dir (fs/create-temp-dir)]
      (testing "printed deps map can be read by Clojure"
        (spit (fs/file tmp-dir "deps.edn") deps)
        (let [cp (sci/with-out-str
                   (deps/clojure ["-Spath"] {:dir (str tmp-dir)}))]
          (is (str/includes? cp "babashka.curl")))
        (fs/delete-tree tmp-dir)))

    ;; The following test /does not work/ because `edn/read-string` scrambles
    ;; the order of the keys of the map when it makes the map.
    ;;
    ;; I need a way to read map keys from a string that retains the order of the
    ;; keys on disk.
    #_
    (testing "keys in dep map are sorted"
      (let [deps-data (edn/read-string deps)]
        (is (sorted? (keys (:deps deps-data))))))))
