(ns gen-ci
  (:require
    [babashka.tasks :as tasks]
    [clj-yaml.core :as yaml]
    [clojure.string :as str]
    [flatland.ordered.map :refer [ordered-map]]))

(def graalvm-version "21.0.2")

(defn run
  ([cmd-name cmd]
   (run cmd-name cmd nil))
  ([cmd-name cmd no-output-timeout]
   (let [base {:run {:name    cmd-name
                     :command cmd}}]
     (if no-output-timeout
       (assoc-in base [:run :no_output_timeout] no-output-timeout)
       base))))

(defn gen-steps
  [shorted? steps]
  (if shorted?
    [(run "Shorted" "echo 'Skipping Run'")]
    steps))

(defn gen-job
  [shorted? conf]
  (if shorted?
    (-> conf
        (dissoc :machine :macos)
        (assoc :resource_class "small" :docker [{:image "ubuntu:latest"}]))
    conf))

(defn pull-submodules
  []
  (run "Pull Submodules" "git submodule init\ngit submodule update"))

(defn deploy
  [shorted?]
  (gen-job shorted?
           (ordered-map
             :resource_class    "large"
             :docker            [{:image "circleci/clojure:lein-2.9.8"}]
             :working_directory "~/repo"
             :environment       {:LEIN_ROOT "true"}
             :steps             (gen-steps
                                  shorted?
                                  [:checkout
                                   (pull-submodules)
                                   {:restore_cache {:keys ["v1-dependencies-{{ checksum \"project.clj\" }}"
                                                           "v1-dependencies-"]}}
                                   {:run ".circleci/script/deploy"}
                                   {:save_cache {:paths ["~/.m2"]
                                                 :key   "v1-dependencies-{{ checksum \"project.clj\" }}"}}]))))

(defn docker
  [shorted?]
  (gen-job
    shorted?
    (ordered-map
      :machine {:image "ubuntu-2004:202111-01"}
      :steps
      (gen-steps
        shorted?
        [:checkout
         (pull-submodules)
         "setup-docker-buildx"
         {:attach_workspace {:at "/tmp"}}
         (run "Build uberjar" "script/uberjar")
         {:run
          {:name        "Build Docker image"
           :environment {:PLATFORMS "linux/amd64,linux/arm64"}
           :command
           "java -jar ./target/babashka-$(cat resources/BABASHKA_VERSION)-standalone.jar .circleci/script/docker.clj"}}]))))

(defn jvm
  [shorted? graalvm-home]
  (gen-job
    shorted?
    (ordered-map
      :docker            [{:image "circleci/clojure:openjdk-11-lein-2.9.8-bullseye"}]
      :working_directory "~/repo"
      :environment       {:LEIN_ROOT         "true"
                          :BABASHKA_PLATFORM "linux"
                          :GRAALVM_VERSION   graalvm-version
                          :GRAALVM_HOME      graalvm-home
                          :BABASHKA_TEST_ENV "jvm"}
      :resource_class    "large"
      :steps
      (gen-steps
        shorted?
        [:checkout
         (pull-submodules)
         {:restore_cache {:keys ["v1-dependencies-{{ checksum \"project.clj\" }}-{{ checksum \"deps.edn\" }}"
                                 "v1-dependencies-"]}}
         (run "Install Clojure" "sudo script/install-clojure")
         (run "Download GraalVM" "script/install-graalvm")
         (run
           "Run JVM tests"
           "export BABASHKA_FEATURE_JDBC=true
export BABASHKA_FEATURE_POSTGRESQL=true
script/test\nscript/run_lib_tests")
         (run "Run as lein command" ".circleci/script/lein")
         (run
           "Create uberjar"
           "mkdir -p /tmp/release
script/uberjar
VERSION=$(cat resources/BABASHKA_VERSION)
jar=target/babashka-$VERSION-standalone.jar
cp $jar /tmp/release
export PATH=$GRAALVM_HOME/bin:$PATH
export JAVA_HOME=$GRAALVM_HOME
java -jar $jar script/reflection.clj
reflection=\"babashka-$VERSION-reflection.json\"
java -jar \"$jar\" --config .build/bb.edn --deps-root . release-artifact \"$jar\"
java -jar \"$jar\" --config .build/bb.edn --deps-root . release-artifact \"$reflection\"")
         {:store_artifacts {:path        "/tmp/release"
                            :destination "release"}}
         {:save_cache {:paths ["~/.m2"]
                       :key   "v1-dependencies-{{ checksum \"project.clj\" }}-{{ checksum \"deps.edn\" }}"}}]))))

(defn unix
  [shorted? static? musl? arch executor-conf resource-class graalvm-home platform]
  (let [env              {:LEIN_ROOT         "true"
                          :GRAALVM_VERSION   "21.0.2"
                          :GRAALVM_HOME      graalvm-home
                          :BABASHKA_PLATFORM (if (= "mac" platform)
                                               "macos"
                                               platform)
                          :BABASHKA_TEST_ENV "native"
                          :BABASHKA_XMX      "-J-Xmx6500m"}
        env              (if (= "aarch64" arch)
                           (assoc env :BABASHKA_ARCH arch)
                           env)
        env              (if static?
                           (assoc env :BABASHKA_STATIC "true")
                           env)
        env              (if musl?
                           (assoc env :BABASHKA_MUSL "true")
                           env)
        env              (if (= "mac" platform)
                           (assoc env :MACOSX_DEPLOYMENT_TARGET 10.13)
                           env)
        base-install-cmd "sudo apt-get update\nsudo apt-get -y install build-essential zlib1g-dev"
        cache-key        (format "%s-%s{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"
                                 platform
                                 (if (= "aarch64" arch)
                                   "aarch64-"
                                   ""))]
    (gen-job shorted?
             (merge
               executor-conf
               (ordered-map
                 :working_directory "~/repo"
                 :environment env
                 :resource_class resource-class
                 :steps (gen-steps shorted?
                                   (filter some?
                                           [:checkout
                                            (when (contains? #{"linux" "linux-aarch64"} platform)
                                              (run "Check max glibc version" "script/check_glibc.sh"))
                                            {:attach_workspace {:at "/tmp"}}
                                            (run "Pull Submodules" "git submodule init\ngit submodule update")
                                            {:restore_cache
                                             {:keys [cache-key]}}
                                            (run "Install Clojure" "sudo script/install-clojure")
                                            (when (= "mac" platform)
                                              (run "Install Leiningen" "script/install-leiningen"))
                                            (when (not= "mac" platform)
                                              (run "Install native dev tools"
                                                   (if (and static? musl? (not= "aarch64" arch))
                                                     (str base-install-cmd "\nsudo -E script/setup-musl")
                                                     base-install-cmd)))
                                            (run "Download GraalVM" "script/install-graalvm")
                                            #_(run "Download iprof" "curl -sLO 'https://github.com/babashka/pgo-profiles/releases/download/2023.10.11/default.iprof'")
                                            (run "Build binary" (if (= "aarch64" arch)
                                                                  "script/uberjar\nscript/compile -H:PageSize=64K # --pgo=default.iprof"
                                                                  "script/uberjar\nscript/compile # --pgo=default.iprof") "30m")
                                            (run "Run tests" "script/test\nscript/run_lib_tests")
                                            (run "Release" ".circleci/script/release")
                                            {:persist_to_workspace {:root  "/tmp"
                                                                    :paths ["release"]}}
                                            {:save_cache
                                             {:paths ["~/.m2" "~/graalvm"]
                                              :key   cache-key}}
                                            {:store_artifacts {:path        "/tmp/release"
                                                               :destination "release"}}
                                            (run "Publish artifact link to Slack"
                                                 "./bb .circleci/script/publish_artifact.clj || true")])))))))

(defn make-config
  [shorted?]
  (let [docker-executor-conf  {:docker [{:image "circleci/clojure:openjdk-11-lein-2.9.8-bullseye"}]}
        machine-executor-conf {:machine {:image "ubuntu-2004:202111-01"}}
        mac-executor-conf     {:macos {:xcode "15.2.0"}}
        linux-graalvm-home    (str "/home/circleci/graalvm-" graalvm-version)
        mac-graalvm-home      (format "/Users/distiller/graalvm-%s/Contents/Home" graalvm-version)]
    (ordered-map
      :version   2.1
      :commands
      {:setup-docker-buildx
       {:steps
        [{:run
          {:name    "Create multi-platform capabale buildx builder"
           :command
           "docker run --privileged --rm tonistiigi/binfmt --install all\ndocker buildx create --name ci-builder --use"}}]}}
      :jobs      (ordered-map
                   :jvm (jvm shorted? linux-graalvm-home)
                   :linux (unix shorted? false false "amd64" docker-executor-conf "large" linux-graalvm-home "linux")
                   :linux-static
                   (unix shorted? true true "amd64" docker-executor-conf "large" linux-graalvm-home "linux")
                   :linux-aarch64-static
                   (unix shorted? true false "aarch64" machine-executor-conf "arm.large" linux-graalvm-home "linux")
                   :mac (unix shorted? false false "amd64" mac-executor-conf "macos.x86.medium.gen2" mac-graalvm-home "mac")
                   :deploy (deploy shorted?)
                   :docker (docker shorted?))
      :workflows (ordered-map
                   :version 2
                   :ci      {:jobs ["jvm"
                                    "linux"
                                    "linux-static"
                                    "mac"
                                    "linux-aarch64-static"
                                    {:deploy {:filters  {:branches {:only "master"}}
                                              :requires ["jvm" "linux"]}}
                                    {:docker {:filters  {:branches {:only "master"}}
                                              :requires ["linux" "linux-static" "linux-aarch64-static"]}}]}))))

(def skip-config
  {:skip-if-only [#".*.md$"
                  #"^logo\/.*$"]})

(defn get-changes
  []
  (-> (tasks/shell {:out :string} "git diff --name-only HEAD~1")
      (:out)
      (str/split-lines)))

(defn irrelevant-change?
  [change regexes]
  (some? (some #(re-matches % change) regexes)))

(defn relevant?
  [change-set regexes]
  (some? (some #(not (irrelevant-change? % regexes)) change-set)))

(defn main
  []
  (let [{:keys [skip-if-only]} skip-config
        changed-files          (get-changes)
        conf                   (make-config (not (relevant? changed-files skip-if-only)))]
    (println (yaml/generate-string conf
                                   :dumper-options
                                   {:flow-style :block}))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))

(comment
  (main)

  (def regexes
    [#".*.md$"
     #".*.clj$" ; ignore clojure files
     #"^logo\/.*$"])

  (:out (tasks/shell {:out :string} "ls"))

  (irrelevant-change? "src/file.png" regexes)

  (re-matches #".*.clj$" "src/file.clj.dfff")

  (re-matches #"^logo\/.*$" "logo/foo/bar.jpg")

  (relevant? ["src/file.clj"] regexes))
