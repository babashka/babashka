(ns babashka.bb-edn-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [working?]}}}}
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(defmacro with-config [cfg & body]
  `(let [temp-dir# (fs/create-temp-dir)
         bb-edn-file# (fs/file temp-dir# "bb.edn")]
     (spit bb-edn-file# ~cfg)
     (binding [test-utils/*bb-edn-path* (str bb-edn-file#)]
       ~@body)))

(deftest babashka-task-test
  (with-config {:tasks {:sum {:task/type :babashka
                              :args ["-e" "(+ 1 2 3)"]}}}
    (let [res (bb :sum)]
      (is (= 6 res)))))

(deftest shell-task-test
  (let [temp-dir (fs/create-temp-dir)
        temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
    (with-config {:tasks {:clean {:task/type :shell
                                  :args ["rm" (str temp-file)]}}}
      (is (fs/exists? temp-file))
      (bb :clean)
      (is (not (fs/exists? temp-file))))))

(deftest do-task-test
  (testing ":and-do"
    (let [temp-dir (fs/create-temp-dir)
          temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
      (with-config {:tasks {:clean {:task/type :shell
                                    :args ["rm" (str temp-file)]}
                            :sum {:task/type :babashka
                                  :args ["-e" "(+ 1 2 3)"]}
                            :all {:task/type :babashka
                                  :args [:do :clean :and-do :sum]}}}
        (is (fs/exists? temp-file))
        (let [res (bb :all)]
          (is (= 6 res)))
        (is (not (fs/exists? temp-file))))))
  (testing ":or-do"
    (with-config {:tasks {:div-by-zero {:task/type :babashka
                                        :args ["-e" "(/ 1 0)"]}
                          :sum {:task/type :babashka
                                :args ["-e" "(+ 1 2 3)"]}
                          :all {:task/type :babashka
                                :args [:do :div-by-zero :and-do :sum]}}}
      (is (thrown-with-msg? Exception #"Divide"
                            (bb :all))))))
