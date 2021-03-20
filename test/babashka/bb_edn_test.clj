(ns babashka.bb-edn-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [working?]}}}}
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(deftest foobar-test
  (let [temp-dir (fs/create-temp-dir)
        temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))
        bb-edn-file (fs/file temp-dir "bb.edn")
        bb-edn `{:tasks {:clean {:task/type :shell
                                 :args ["rm" ~(str temp-file)]}}}]
    (spit bb-edn-file bb-edn)
    (is (fs/exists? temp-file))
    (binding [test-utils/*bb-edn-path* (str bb-edn-file)]
      (bb :clean))
    (is (not (fs/exists? temp-file)))))

