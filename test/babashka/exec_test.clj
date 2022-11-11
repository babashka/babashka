(ns babashka.exec-test
  (:require
   [babashka.test-utils :as u]
   [cheshire.core :as cheshire]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [& args]
  (apply u/bb nil args))

(deftest exec-test
  (is (= {:foo 1} (edn/read-string (bb "-x" "prn" "--foo" "1"))))
  (is (thrown? Exception (bb "-x" "json/generate-string" "--foo" "1")))
  (is (= {:foo 1} (cheshire/parse-string
                   (edn/read-string
                    (bb "-x" "cheshire.core/generate-string" "--foo" "1")) true))))

(deftest tasks-exec-test
  (u/with-config
    "{:deps {}
      :tasks {foo (exec 'clojure.core/prn)}}"
    (is (= {:dude 1} (edn/read-string (bb "run" "foo" "--dude" "1")))))
  (u/with-config
    "{:deps {}
      :tasks {foo (exec 'clojure.core/prn)}}"
    (is (= {:dude 1} (edn/read-string (bb "run" "foo" "--dude" "1")))))
  (u/with-config
    "{:deps {}
      :tasks {foo {:org.babashka/cli {:coerce {:dude []}}
                   :task (exec 'clojure.core/prn)}}}"
    (is (= {:dude [1]} (edn/read-string (bb "run" "foo" "--dude" "1")))))
  (u/with-config
    "{:deps {}
      :tasks {foo {:task (exec 'babashka.exec-test/exec-test)}}}"
    (is (= {:foo [1], :bar :yeah}
           (edn/read-string (bb "-cp" "test-resources" "run" "foo" "--foo" "1" "--bar" "yeah")))))
  (testing "task exec args"
    (u/with-config
      "{:deps {}
      :tasks {foo {:exec-args {:foo :bar}
                   :task (exec 'babashka.exec-test/exec-test)}}}"
      (is (= {:foo :bar, :bar :yeah}
             (edn/read-string (bb "-cp" "test-resources" "run" "foo" "--bar" "yeah"))))))
  (testing "meta"
    (u/with-config
      "{:deps {}
        :tasks {foo {:task (exec 'babashka.exec-test/exec-test)}}}"
      (is (= #:org.babashka{:cli {:args ["dude"]}}
             (edn/read-string (bb "-cp" "test-resources" "run" "foo" "dude" "--bar" "yeah" "--meta")))))))
