(ns babashka.deps-test
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as test-utils]
   [borkdude.deps :as deps]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (let [edn-str (apply test-utils/bb nil (map str args))]
    (edn/read-string
     {:readers *data-readers*
      :eof nil}
     edn-str)))

(deftest resolver-switch-test
  ;; BABASHKA_DEPS_RESOLVER picks the resolver; unset means the java that
  ;; deps.clj spawns, bb means tools.deps in this process. Both resolve
  ;; the same dependency.
  (doseq [resolver ["jvm" "bb"]]
    (testing resolver
      (is (= 3 (bb (format "
(babashka.deps/add-deps '{:deps {medley/medley {:mvn/version \"1.3.0\"}}}
                        {:force true :extra-env {\"BABASHKA_DEPS_RESOLVER\" \"%s\"}})
(require '[medley.core :as m])
(m/find-first odd? [2 3 4])
" resolver))))))
  (testing ":deps-resolver in the deps map, no environment needed"
    (is (= 3 (bb "
(babashka.deps/add-deps '{:deps {medley/medley {:mvn/version \"1.3.0\"}} :deps-resolver :bb}
                        {:force true})
(require '[medley.core :as m])
(m/find-first odd? [2 3 4])
"))))
  (testing ":deps-resolver in bb.edn"
    (let [tmp (fs/create-temp-dir)
          bb-edn (fs/file tmp "bb.edn")]
      (spit bb-edn "{:deps {medley/medley {:mvn/version \"1.3.0\"}} :deps-resolver :bb}")
      (is (= 3 (edn/read-string
                (test-utils/bb nil "--config" (str bb-edn) "-e"
                               "(require '[medley.core :as m]) (m/find-first odd? [2 3 4])")))))))

(defn- tool-config!
  "A config dir under tmp with one tool named name, rooted at a fresh
  project. Returns [config-dir src-dir]."
  [tmp name]
  (let [config (fs/file tmp (str "config-" name))
        tool-root (fs/file tmp name)]
    (fs/create-dirs (fs/file config "tools"))
    (fs/create-dirs (fs/file tool-root "src"))
    (spit (fs/file tool-root "deps.edn") "{:paths [\"src\"]}")
    (spit (fs/file config "tools" (str name ".edn"))
          (pr-str {:lib (symbol "my" name) :coord {:local/root (str tool-root)}}))
    ;; canonical: tools.deps canonicalizes a :local/root, and the temp dir
    ;; is a symlink on macOS and an 8.3 short name on Windows runners
    [(str config) (str (fs/canonicalize (fs/file tool-root "src")))]))

(deftest resolver-from-process-env-test
  ;; the CI leg that sets BABASHKA_DEPS_RESOLVER=bb must actually resolve
  ;; in-process: with no java to be found, only that resolver can succeed.
  ;; JAVA_CMD "" keeps deps.clj from looking for java and gives it nothing
  ;; runnable.
  (when (= "bb" (System/getenv "BABASHKA_DEPS_RESOLVER"))
    (is (= 3 (bb "
(babashka.deps/add-deps '{:deps {medley/medley {:mvn/version \"1.3.0\"}}}
                        {:force true :extra-env {\"PATH\" \"/nonexistent\" \"JAVA_HOME\" \"\" \"JAVA_CMD\" \"\"}})
(require '[medley.core :as m])
(m/find-first odd? [2 3 4])
")))))

(deftest tool-descriptor-in-per-call-config-test
  ;; a named tool resolves through <config-dir>/tools/<name>.edn, and the
  ;; config dir of the resolve comes from its own environment, CLJ_CONFIG
  ;; included, not from bb's process environment. -Srepro drops the user
  ;; deps.edn, not the config dir.
  (let [tmp (fs/create-temp-dir)
        [config src] (tool-config! tmp "mytool")]
    (doseq [args [["-Sforce" "-Spath" "-Tmytool"]
                  ["-Srepro" "-Sforce" "-Spath" "-Tmytool"]]]
      (testing (str/join " " args)
        (let [cp (bb (pr-str `(with-out-str
                                (babashka.deps/clojure ~args
                                                       {:extra-env {"CLJ_CONFIG" ~config
                                                                    "BABASHKA_DEPS_RESOLVER" "bb"}}))))]
          (is (str/includes? (str cp) src)))))))

(deftest concurrent-config-dirs-test
  ;; two resolves with their own CLJ_CONFIG at the same time each find
  ;; their own tool
  (let [tmp (fs/create-temp-dir)
        [config-a src-a] (tool-config! tmp "toola")
        [config-b src-b] (tool-config! tmp "toolb")
        [outs-a outs-b]
        (bb (pr-str `(let [run# (fn [config# tool#]
                                  (with-out-str
                                    (babashka.deps/clojure ["-Sforce" "-Spath" (str "-T" tool#)]
                                                           {:extra-env {"CLJ_CONFIG" config#
                                                                        "BABASHKA_DEPS_RESOLVER" "bb"}})))
                           a# (future (vec (repeatedly 3 #(run# ~config-a "toola"))))
                           b# (future (vec (repeatedly 3 #(run# ~config-b "toolb"))))]
                       [@a# @b#])))]
    (is (every? #(str/includes? % src-a) outs-a))
    (is (every? #(str/includes? % src-b) outs-b))))

(deftest settings-read-the-call-environment-test
  ;; ${env.NAME} in settings.xml reads the environment of the call, so a
  ;; mirror URL from :extra-env names the repository in the message
  (let [tmp (fs/create-temp-dir)
        home (fs/file tmp "home")
        mirror (fs/file tmp "mirror")
        _ (fs/create-dirs (fs/file home ".m2"))
        _ (fs/create-dirs mirror)
        ;; a file URL the way java writes one, so it holds on Windows too
        mirror-url (str (.toURI (fs/file mirror)))]
    (spit (fs/file home ".m2" "settings.xml")
          "<settings><mirrors><mirror><id>m</id><url>${env.MIRROR_URL}</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>")
    (let [message (bb (pr-str `(let [real-home# (System/getProperty "user.home")]
                                 (System/setProperty "user.home" ~(str home))
                                 (try
                                   (babashka.deps/add-deps '{:deps {nope/nope {:mvn/version "1.0.0"}}}
                                                           {:force true
                                                            :extra-env {"BABASHKA_DEPS_RESOLVER" "bb"
                                                                        "MIRROR_URL" ~mirror-url}})
                                   (catch Exception e# (ex-message e#))
                                   (finally (System/setProperty "user.home" real-home#))))))]
      ;; the first artifact tools.deps asks for is not the one under
      ;; test but a root dep, so only the repository list is checked;
      ;; central and clojars behind one mirror are one entry
      (is (str/starts-with? (str message) "Could not find artifact "))
      (is (str/ends-with? (str message) (str " in m (" mirror-url ")"))))))

(deftest task-inherits-resolver-test
  ;; a task's :extra-deps carry no :deps-resolver; the project's setting in
  ;; bb.edn applies. The ambient make-classpath fn throws, so only the
  ;; in-process resolver gets the task running. The fresh :local/root keeps
  ;; the classpath cache out of it.
  (let [tmp (fs/create-temp-dir)
        lib (fs/file tmp "lib")
        bb-edn (fs/file tmp "bb.edn")]
    (fs/create-dirs (fs/file lib "src" "my"))
    (spit (fs/file lib "deps.edn") "{:paths [\"src\"]}")
    (spit (fs/file lib "src" "my" "lib.clj") "(ns my.lib) (def x 3)")
    (spit bb-edn (pr-str {:deps-resolver :bb
                          :tasks {'find-x {:extra-deps {'my/lib {:local/root (str lib)}}
                                           :requires '([my.lib :as l])
                                           :task '(prn l/x)}}}))
    (is (= 3 (edn/read-string
              (binding [deps/*make-classpath-fn* (fn [_] (throw (Exception. "the java resolver ran")))]
                (test-utils/bb nil "--config" (str bb-edn) "find-x")))))))

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
      (is (fs/exists? libs-dir2))))
  (testing "GITLIBS can change between two resolves in one process"
    (let [tmp-dir (fs/create-temp-dir)
          libs-dir (fs/file tmp-dir ".gitlibs-a")
          libs-dir2 (fs/file tmp-dir ".gitlibs-b")
          dep '{:deps {babashka/process {:git/url "https://github.com/babashka/process" :sha "4c6699d06b49773d3e5c5b4c11d3334fb78cc996"}}}]
      (bb (pr-str `(do (babashka.deps/add-deps '~dep {:force true :extra-env {"GITLIBS" ~(str libs-dir) "BABASHKA_DEPS_RESOLVER" "bb"}})
                       (babashka.deps/add-deps '~dep {:force true :extra-env {"GITLIBS" ~(str libs-dir2) "BABASHKA_DEPS_RESOLVER" "bb"}})
                       nil)))
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
  (is (= "6\n" (test-utils/normalize (bb "
(require '[babashka.deps :as deps])
(require '[babashka.process :as p])

(-> (babashka.deps/clojure {:out :string} \"-M\" \"-e\" \"(+ 1 2 3)\")
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
    (let [tmp-dir (fs/create-temp-dir)
          libs-dir (fs/file tmp-dir ".gitlibs")
          libs-dir2 (fs/file tmp-dir ".gitlibs2")
            ; nested quotes need different escaping for Windows based on jvm/native test
          escape-quote (if test-utils/native? "\\\\\"" "\\\"")
          deps-map (str/join escape-quote [" \"{:deps {babashka/process {:git/url "
                                           "https://github.com/babashka/process" " :sha "
                                           "4c6699d06b49773d3e5c5b4c11d3334fb78cc996" "}}}\""])
          template (str "(do (babashka.deps/clojure [\"-Sforce\" \"-Spath\" \"-Sdeps\"" deps-map "]
                                     {:out :string :env-key {\"PATH\"    (System/getenv \"PATH\")
                                                             \"GITLIBS\" :gitlibs}}) nil)")]
      (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir)))
              (str/replace ":env-key" ":env")))
      (bb (-> template (str/replace ":gitlibs" (pr-str (str libs-dir2)))
              (str/replace ":env-key" ":extra-env")))
      (is (fs/exists? libs-dir))
      (is (fs/exists? libs-dir2)))))
