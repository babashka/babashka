(ns com.wsscode.misc.uuid-test
  (:require
    [clojure.test :refer [deftest is are run-tests testing]]
    [com.wsscode.misc.uuid :as uuid]))

(deftest cljc-random-uuid-test
  (is (uuid? (uuid/cljc-random-uuid))))
