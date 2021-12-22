(ns environ.core-test
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [goog.object :as obj])
            [clojure.test :refer [deftest is testing]]
            [environ.core :as environ]))

#?(:cljs (def nodejs? (exists? js/require)))
#?(:cljs (def fs (when nodejs? (js/require "fs"))))
#?(:cljs (def process (when nodejs? (js/require "process"))))

(defn- delete-file [f]
  #?(:clj (.delete (io/file f))
     :cljs (when (.existsSync fs f)
             (.unlinkSync fs f))))

(defn- get-env [x]
  #?(:clj (System/getenv x)
     :cljs (obj/get (.-env process) x)))

#?(:clj (defn refresh-ns []
          (remove-ns 'environ.core)
          ;; BB-TEST-PATCH: bb doesn't have *loaded-libs*
          ; (dosync (alter @#'clojure.core/*loaded-libs* disj 'environ.core))
          (require 'environ.core)))

#?(:clj (defn refresh-env []
          (refresh-ns)
          (var-get (find-var 'environ.core/env))))

#?(:cljs (defn refresh-env []
           (set! environ/env (environ/read-env))
           environ/env))

#?(:cljs (defn- spit [f data]
           (.writeFileSync fs f data)))

(deftest test-env
  (if #?(:clj true :cljs nodejs?)
    (testing "JVM and Node.js environment"
      (testing "env variables"
        (let [env (refresh-env)]
          (is (= (:user env) (get-env "USER")))
          (is (= (:java-arch env) (get-env "JAVA_ARCH")))))
      #?(:clj (testing "system properties"
                (let [env (refresh-env)]
                  (is (= (:user-name env) (System/getProperty "user.name")))
                  (is (= (:user-country env) (System/getProperty "user.country"))))))
      (testing "env file"
        (spit ".lein-env" (prn-str {:foo "bar"}))
        (let [env (refresh-env)]
          (is (= (:foo env) "bar"))))
      (testing "env file with irregular keys"
        (spit ".lein-env" (prn-str {:foo.bar "baz"}))
        (let [env (refresh-env)]
          (is (= (:foo-bar env) "baz"))))
      (testing "env file with irregular keys"
        (spit ".lein-env" "{:foo #=(str \"bar\" \"baz\")}")
        (is (thrown? #?(:clj RuntimeException :cljs js/Error) (refresh-env))))
      (testing "env file with non-string values"
        (spit ".lein-env" (prn-str {:foo 1 :bar :baz}))
        (let [env (refresh-env)]
          (is (= (:foo env) "1"))
          (is (= (:bar env) ":baz")))))
    (testing "non Node.js environment"
      (is (= environ/env {})))))
