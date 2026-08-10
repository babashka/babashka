(ns babashka.tasks-plan-test
  "What a bb.edn compiles to, asked directly. Every case here used to need a bb
  subprocess and a task that wrote a file to observe itself, which is why the
  combinations below went untested."
  (:require [babashka.impl.tasks :as tasks]
            [clojure.test :as test :refer [deftest is testing]]))

(defn- plan
  ([tasks target] (plan tasks target false))
  ([tasks target parallel?]
   (tasks/plan tasks (symbol target) {:parallel? parallel?})))

(defn- kinds [p]
  (mapv (juxt :name :kind) (:nodes p)))

(deftest order-test
  (testing "dependencies come before the target, deepest first"
    (is (= '[[-a :plain] [-b :plain] [tst :target]]
           (kinds (plan '{-a (println "a")
                          -b {:depends [-a] :task (println "b")}
                          tst {:depends [-b] :task (println "t")}}
                        "tst")))))
  (testing "a diamond names each dependency once"
    (is (= '[[-a :plain] [-b :plain] [-c :plain] [tst :target]]
           (kinds (plan '{-a (println "a")
                          -b {:depends [-a] :task (println "b")}
                          -c {:depends [-a] :task (println "c")}
                          tst {:depends [-b -c] :task (println "t")}}
                        "tst"))))))

(deftest cli-dep-kind-test
  (testing "a CLI dependency of a dispatching target is a :cli-dep"
    (is (= '[[-a :cli-dep] [tst :target]]
           (kinds (plan '{-a {:exec-fn some.ns/g}
                          tst {:depends [-a] :exec-fn some.ns/f}}
                        "tst")))))
  (testing "the same dependency under a plain target stays :plain -- nothing
            parses, so there are no options to hand its handler"
    (is (= '[[-a :plain] [tst :target]]
           (kinds (plan '{-a {:exec-fn some.ns/g}
                          tst {:depends [-a] :task (println "t")}}
                        "tst")))))
  (testing "a plain dependency of a CLI target stays :plain"
    (is (= '[[-a :plain] [tst :target]]
           (kinds (plan '{-a (println "a")
                          tst {:depends [-a] :exec-fn some.ns/f}}
                        "tst")))))
  (testing "a :cmd target dispatches too, so its dependency is a :cli-dep"
    (is (= '[[-a :cli-dep] [tst :target]]
           (kinds (plan '{-a {:exec-fn some.ns/g}
                          tst {:depends [-a] :cmd {"sub" {:exec-fn some.ns/f}}}}
                        "tst"))))))

(deftest errors-test
  (testing "a cycle is reported, not emitted"
    (is (re-find #"Cyclic task"
                 (:error (plan '{-a {:depends [tst] :task nil}
                                 tst {:depends [-a] :task nil}}
                               "tst")))))
  (testing "a missing dependency names itself"
    (is (= "No such task: -nope"
           (:error (plan '{tst {:depends [-nope] :task nil}} "tst")))))
  (testing "a :cmd dependency of a dispatching target has no handler to call"
    (is (re-find #":depends cannot name -tree"
                 (:error (plan '{-tree {:cmd {"sub" {:exec-fn some.ns/g}}}
                                 tst {:depends [-tree] :exec-fn some.ns/f}}
                               "tst")))))
  (testing "but a :cmd dependency that has a body is not an error: the body is
            what it contributes, and a plain target never wanted a handler"
    (is (nil? (:error (plan '{-tree {:cmd {"sub" {:exec-fn some.ns/g}}
                                     :task (println "tree")}
                              tst {:depends [-tree] :task (println "t")}}
                            "tst"))))))

(deftest classpath-test
  (testing "paths, deps and requires add up over the whole graph, so a
            dependency's handler can live on a dependency's classpath"
    (let [p (plan '{-a {:task nil :extra-paths ["a"] :requires ([a.core])}
                    -b {:depends [-a] :task nil :extra-paths ["b"]
                        :extra-deps {b/b {:mvn/version "1"}}}
                    tst {:depends [-b] :task nil :extra-paths ["t"]
                         :extra-deps {t/t {:mvn/version "2"}}
                         :requires ([t.core])}}
                  "tst")]
      (is (= ["a" "b" "t"] (:extra-paths p)))
      (is (= '{b/b {:mvn/version "1"} t/t {:mvn/version "2"}} (:extra-deps p)))
      (is (= '[[a.core] [t.core]] (:requires p))))))

(deftest dep-nodes-test
  (testing "only CLI dependencies contribute a node, in dependency order"
    (is (= ["-a" "-c"]
           (mapv first (:dep-nodes (plan '{-a {:exec-fn some.ns/g}
                                           -b {:depends [-a] :task (println "b")}
                                           -c {:depends [-b] :exec-fn some.ns/h}
                                           tst {:depends [-c] :exec-fn some.ns/f}}
                                         "tst")))))))
