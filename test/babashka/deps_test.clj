(ns babashka.deps-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(deftest dependency-test (is (= #{:a :c :b} (bb "
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {com.stuartsierra/dependency {:mvn/version \"1.0.0\"}}})

(require '[com.stuartsierra.dependency :as dep])

(def g1 (-> (dep/graph)
            (dep/depend :b :a)
            (dep/depend :c :b)
            (dep/depend :c :a)
            (dep/depend :d :c)))

(dep/transitive-dependencies g1 :d)
"))))

(deftest clojure-test (is (true? (bb "
(require '[babashka.deps :as deps])
(require '[clojure.string :as str])
(str/includes?
  (with-out-str (babashka.deps/clojure \"-Stree\"))
  \"org.clojure/clojure\")
"))))
