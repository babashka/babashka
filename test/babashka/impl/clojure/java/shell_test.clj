(ns babashka.impl.clojure.java.shell-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.test-utils :as test-utils]
            [clojure.string :as str]))

(deftest with-sh-env-test
  (is (= "\"BAR\""
         (str/trim (test-utils/bb nil "
(-> (shell/with-sh-env {:FOO \"BAR\"}
      (shell/sh \"bash\" \"-c\" \"echo $FOO\"))
    :out
    str/trim)"))))
  (is (str/includes? (str/trim (test-utils/bb nil "
(-> (shell/with-sh-dir \"logo\"
      (shell/sh \"ls\"))
    :out)"))
                     "icon.svg")))
