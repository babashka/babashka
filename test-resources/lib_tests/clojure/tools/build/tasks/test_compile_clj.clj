;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-compile-clj
  (:require
    [clojure.test :refer :all :as test]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.tasks.compile-clj :as compile-clj]
    [clojure.tools.build.test-util :refer :all]))

(deftest test-topo
  ;; deps: a -> b -> c, d
  ;; expect: c b a (reverse topo sort), then d at the end
  (is (= '[c b a d]
        ;; BB-TEST-PATCH: the tests run from babashka's root
        (#'compile-clj/nses-in-topo [(jio/file "test-resources/lib_tests/clojure/tools/build/test-data/nses/src")]))))

(deftest test-compile
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/compile-clj {:class-dir "target/classes"
                      :src-dirs ["src"]
                      :basis (api/create-basis nil)})
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar.class")))))
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar__init.class")))))
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar$hello.class")))))))

;; use :src-dirs from basis paths
(deftest test-compile-basis-paths
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/compile-clj {:class-dir "target/classes"
                      :basis (api/create-basis nil)})
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar.class")))))
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar__init.class")))))
    (is (true? (.exists (jio/file (project-path "target/classes/foo/bar$hello.class")))))))

(defn find-java []
  (-> (api/process {:command-args [(if windows? "where" "which") "java"]
                    :out :capture})
      :out
      str/split-lines
      first))

(deftest test-compile-passthrough-opts
  (when-not (str/starts-with? (System/getProperty "java.version") "1.")
    (let [java-cmd (find-java)]
      (with-test-dir "test-data/p1"
        (api/set-project-root! (.getAbsolutePath *test-dir*))
        (api/compile-clj {:class-dir "target/classes"
                          :src-dirs ["src"]
                          :basis (api/create-basis nil)
                          ;; pass these through to java command
                          :java-opts ["-Dhi=there"]
                          :use-cp-file :always
                          :java-cmd java-cmd})
        (is (true? (.exists (jio/file (project-path "target/classes/foo/bar.class")))))
        (is (true? (.exists (jio/file (project-path "target/classes/foo/bar__init.class")))))
        (is (true? (.exists (jio/file (project-path "target/classes/foo/bar$hello.class")))))))))

(deftest test-turn-off-assert-with-bindings
  (with-test-dir "test-data/assert"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (let [basis (api/create-basis nil)
          invoke #(-> {:basis basis :main 'clojure.main :main-args ["-e" "((requiring-resolve 'foo.check-assert/f) 100)"]}
                    api/java-command
                    (merge {:out :capture, :err :ignore})
                    api/process)
          compile-params {:class-dir "target/classes" :src-dirs ["src"] :basis basis}]

      ;; by default, assertions are on when compiling, then invocation fails (assertion expects keyword)
      (api/compile-clj compile-params) ;; no :bindings set
      (is (= {:exit 1} (invoke)))

      ;; recompile with binding to turn off assertions, then it passes (assertion not checked)
      (api/delete {:path "target/classes"})
      (api/compile-clj (assoc compile-params :bindings {#'clojure.core/*assert* false})) ;; turn off asserts
      (is (= {:exit 0, :out (str "100" (System/lineSeparator))} (invoke))))))

(deftest test-capture-reflection
  (with-test-dir "test-data/reflecting"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (let [basis (api/create-basis nil)
          compile-params {:class-dir "target/classes"
                          :src-dirs ["src"]
                          :basis basis
                          :ns-compile ['foo.bar]}]

      ;; by default, reflection does not warn
      (is (nil? (api/compile-clj compile-params))) ;; no :bindings set

      ;; compile with reflection warnings and capture the error output
      (api/delete {:path "target/classes"})
      (is (str/starts-with?
            (:err
              (api/compile-clj (merge compile-params
                                 {:bindings {#'clojure.core/*warn-on-reflection* true}
                                  :err :capture})))
            "Reflection warning")))))

(deftest test-accidental-basis-delay
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (is (thrown? clojure.lang.ExceptionInfo
          (api/compile-clj {:class-dir "target/classes"
                            :src-dirs ["src"]
                            :basis (delay (api/create-basis nil))})))))

(comment
  (run-tests)
  )
