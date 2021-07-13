(ns babashka.impl.clojure.java.shell-test
  (:require [babashka.main :as main]
            [babashka.test-utils :as test-utils]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))


(deftest ^:skip-windows with-sh-env-test
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

(deftest ^:windows-only win-with-sh-env-test
  (when main/windows?
    (is (= "\"BAR\""
          (str/trim (test-utils/bb nil "
(-> (shell/with-sh-env {:FOO \"BAR\"}
      (shell/sh \"cmd\" \"/c\" \"echo %FOO%\"))
    :out
    str/trim)"))))
    (is (str/includes? (str/trim (test-utils/bb nil "
(-> (shell/with-sh-dir \"logo\"
      (shell/sh \"cmd\" \"/c\" \"dir\"))
    :out)"))
          "icon.svg"))))
