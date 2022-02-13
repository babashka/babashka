(ns borkdude.deps.smoke-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.curl :as curl]))


(def windows? (-> (System/getProperty "os.name")
                (str/lower-case)
                (str/includes? "win")))

(deftest basic-test
  (spit "deps_test.clj"
        (:body (curl/get "https://raw.githubusercontent.com/borkdude/deps.clj/master/deps.clj"
                         (if windows? {:compressed false} {}))))

  (binding [*command-line-args* ["-Sdescribe"]]
    (load-file "deps_test.clj"))

  (.delete (io/file "deps_test.clj")))
