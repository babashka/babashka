(ns babashka.deps-test
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(deftest dependency-test
  (is (= #{:a :c :b} (bb "
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {com.stuartsierra/dependency {:mvn/version \"1.0.0\"}}})

(require '[com.stuartsierra.dependency :as dep])

(def g1 (-> (dep/graph)
            (dep/depend :b :a)
            (dep/depend :c :b)
            (dep/depend :c :a)
            (dep/depend :d :c)))

(dep/transitive-dependencies g1 :d)
")))
  (testing "GITLIBS can set location of .gitlibs dir"
    (let [tmp-dir (fs/create-temp-dir)
          libs-dir (fs/file tmp-dir ".gitlibs")
          libs-dir2 (fs/file tmp-dir ".gitlibs2")]
      (bb (pr-str `(do (babashka.deps/add-deps '{:deps {babashka/process {:git/url "https://github.com/babashka/process" :sha "4c6699d06b49773d3e5c5b4c11d3334fb78cc996"}}}
                                               {:force true
                                                :env {"PATH" (System/getenv "PATH")
                                                      "JAVA_HOME" (System/getenv "JAVA_HOME")
                                                      "GITLIBS" ~(str libs-dir)}}) nil)))
      (bb (pr-str `(do (babashka.deps/add-deps '{:deps {babashka/process {:git/url "https://github.com/babashka/process" :sha "4c6699d06b49773d3e5c5b4c11d3334fb78cc996"}}}
                                               {:force true
                                                :extra-env {"GITLIBS" ~(str libs-dir2)}}) nil)))
      (is (fs/exists? libs-dir))
      (is (fs/exists? libs-dir2)))))

(deftest clojure-test
  (testing "-Stree prints to *out*"
    (is (true? (bb "
(require '[babashka.deps :as deps])
(require '[clojure.string :as str])
(str/includes?
  (with-out-str (babashka.deps/clojure [\"-Stree\"]))
  \"org.clojure/clojure\")
"))))
  (testing "-P does not exit babashka script"
    (is (true? (bb "
(require '[babashka.deps :as deps])
(require '[clojure.string :as str])
(babashka.deps/clojure [\"-P\"])
true
"))))
  (is (= "6\n" (test-utils/normalize (bb "
(require '[babashka.deps :as deps])
(require '[babashka.process :as p])

(-> (babashka.deps/clojure [\"-M\" \"-e\" \"(+ 1 2 3)\"] {:out :string})
    (p/check)
    :out)
"))))
  (when-not test-utils/native?
    (is (thrown-with-msg? Exception #"Option changed" (bb "
(require '[babashka.deps :as deps])
(babashka.deps/clojure [\"-Sresolve-tags\"])
"))))
  (is (true? (bb "
(= 5 (:exit @(babashka.deps/clojure [] {:in \"(System/exit 5)\" :out :string})))")))
  (testing "start from other directory"
    (is (= {1 {:id 1}, 2 {:id 2}}
           (edn/read-string (bb "
(:out @(babashka.deps/clojure [\"-M\" \"-e\" \"(require 'medley.core) (medley.core/index-by :id [{:id 1} {:id 2}])\"] {:out :string :dir \"test-resources/clojure-dir-test\"}))")))))
  (testing "GITLIBS can set location of .gitlibs dir"
    ;; TODO: workaround for failing test on Windows
    (when-not (and test-utils/windows?
                   test-utils/native?)
      (let [tmp-dir (fs/create-temp-dir)
            libs-dir (fs/file tmp-dir ".gitlibs")
            libs-dir2 (fs/file tmp-dir ".gitlibs2")
            template (pr-str '(do (babashka.deps/clojure ["-Sforce" "-Spath" "-Sdeps" "{:deps {babashka/process {:git/url \"https://github.com/babashka/process\" :sha \"4c6699d06b49773d3e5c5b4c11d3334fb78cc996\"}}}"]
                                                         {:out :string :env-key {"PATH" (System/getenv "PATH")
                                                                                 "JAVA_HOME" (System/getenv "JAVA_HOME")
                                                                                 "GITLIBS" :gitlibs}}) nil))]
        (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir)))
                (str/replace ":env-key" ":env")))
        (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir2)))
                (str/replace ":env-key" ":extra-env")))
        (is (fs/exists? libs-dir))
        (is (fs/exists? libs-dir2))))))

(deftest ^:windows-only win-clojure-test
    (testing "GITLIBS can set location of .gitlibs dir"
      (let [tmp-dir   (fs/create-temp-dir)
            libs-dir  (fs/file tmp-dir ".gitlibs")
            libs-dir2 (fs/file tmp-dir ".gitlibs2")
            ; nested quotes need different escaping for Windows based on jvm/native test
            escape-quote (if test-utils/native? "\\\\\"" "\\\"")
            deps-map (str/join escape-quote [" \"{:deps {babashka/process {:git/url "
                       "https://github.com/babashka/process" " :sha "
                       "4c6699d06b49773d3e5c5b4c11d3334fb78cc996" "}}}\""])
            template  (str "(do (babashka.deps/clojure [\"-Sforce\" \"-Spath\" \"-Sdeps\"" deps-map "]
                                     {:out :string :env-key {\"PATH\"    (System/getenv \"PATH\")
                                                             \"GITLIBS\" :gitlibs}}) nil)")]
        (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir)))
              (str/replace ":env-key" ":env")))
        (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir2)))
              (str/replace ":env-key" ":extra-env")))
        (is (fs/exists? libs-dir))
        (is (fs/exists? libs-dir2)))))
