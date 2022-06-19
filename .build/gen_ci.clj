(ns gen-ci
  (:require
    [clj-yaml.core :as yaml]
    [flatland.ordered.map :refer [ordered-map]]))

(defn run
  ([cmd-name cmd]
   (run cmd-name cmd nil))
  ([cmd-name cmd no-output-timeout]
   (let [base {:run {:name    cmd-name
                     :command cmd}}]
     (if no-output-timeout
       (assoc-in base [:run :no_output_timeout] no-output-timeout)
       base))))

(defn pull-submodules
  []
  (run "Pull Submodules" "git submodule init\ngit submodule update"))

(defn deploy
  []
  (ordered-map
    :resource_class    "large"
    :docker            [{:image "circleci/clojure:lein-2.9.8"}]
    :working_directory "~/repo"
    :environment       {:LEIN_ROOT "true"}
    :steps             ["checkout"
                        (pull-submodules)
                        {:restore_cache {:keys ["v1-dependencies-{{ checksum \"project.clj\" }}"
                                                "v1-dependencies-"]}}
                        {:run ".circleci/script/deploy"}
                        {:save_cache {:paths ["~/.m2"]
                                      :key   "v1-dependencies-{{ checksum \"project.clj\" }}"}}]))

(defn docker
  []
  (ordered-map
    :machine {:image "ubuntu-2004:202111-01"}
    :steps
    ["checkout"
     (pull-submodules)
     "setup-docker-buildx"
     {:attach_workspace {:at "/tmp"}}
     (run "Build uberjar" "script/uberjar")
     {:run
      {:name        "Build Docker image"
       :environment {:PLATFORMS "linux/amd64,linux/arm64"}
       :command
       "java -jar ./target/babashka-$(cat resources/BABASHKA_VERSION)-standalone.jar .circleci/script/docker.clj"}}]))

(defn jvm
  []
  (ordered-map
    :docker            [{:image "circleci/clojure:openjdk-11-lein-2.9.8-bullseye"}]
    :working_directory "~/repo"
    :environment       {:LEIN_ROOT         "true"
                        :BABASHKA_PLATFORM "linux"}
    :resource_class    "large"
    :steps
    [:checkout
     (pull-submodules)
     {:restore_cache {:keys ["v1-dependencies-{{ checksum \"project.clj\" }}-{{ checksum \"deps.edn\" }}"
                             "v1-dependencies-"]}}
     (run "Install Clojure" "sudo script/install-clojure")
     (run
       "Run JVM tests"
       "export BABASHKA_FEATURE_JDBC=true\nexport BABASHKA_FEATURE_POSTGRESQL=true\nscript/test\nscript/run_lib_tests")
     (run "Run as lein command" ".circleci/script/lein")
     (run
       "Create uberjar"
       "mkdir -p /tmp/release\nscript/uberjar\nVERSION=$(cat resources/BABASHKA_VERSION)\njar=target/babashka-$VERSION-standalone.jar\ncp $jar /tmp/release
java -jar $jar script/reflection.clj\nreflection=\"babashka-$VERSION-reflection.json\"\njava -jar \"$jar\" --config .build/bb.edn --deps-root . release-artifact \"$jar\"
java -jar \"$jar\" --config .build/bb.edn --deps-root . release-artifact \"$reflection\"")
     {:store_artifacts {:path        "/tmp/release"
                        :destination "release"}}
     {:save_cache {:paths ["~/.m2"]
                   :key   "v1-dependencies-{{ checksum \"project.clj\" }}-{{ checksum \"deps.edn\" }}"}}]))

(defn unix
  [static? musl? arch executor-conf resource-class graalvm-home platform]
  (let [base-env         {:LEIN_ROOT                "true"
                          :GRAALVM_VERSION          "22.1.0"
                          :GRAALVM_HOME             graalvm-home
                          :BABASHKA_PLATFORM        platform
                          :BABASHKA_TEST_ENV        "native"
                          :BABASHKA_ARCH            arch
                          :BABASHKA_XMX             "-J-Xmx6500m"
                          :MACOSX_DEPLOYMENT_TARGET 10.13}
        base-install-cmd "sudo apt-get update\nsudo apt-get -y install build-essential zlib1g-dev"
        cache-key        (format "%s-%s{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"
                                 platform
                                 (if (= "aarch64" arch)
                                   "aarch64-"
                                   ""))]
    (merge
      executor-conf
      (ordered-map
        :working_directory "~/repo"
        :environment       (if (and static? musl?)
                             (assoc base-env :BABASHKA_STATIC "true" :BABASHKA_MUSL "true")
                             base-env)
        :resource_class    resource-class
        :steps             (filter some?
                                   [:checkout
                                    {:attach_workspace {:at "/tmp"}}
                                    (run "Pull Submodules" "git submodule init\ngit submodule update")
                                    {:restore_cache
                                     {:keys [cache-key]}}
                                    (run "Install Clojure" "sudo script/install-clojure")
                                    (when (= "mac" platform)
                                      (run "Install Leiningen" "script/install-leiningen"))
                                    (when (not= "mac" platform)
                                      (run "Install native dev tools"
                                           (if (and static? musl?)
                                             (str base-install-cmd "\nsudo -E script/setup-musl")
                                             base-install-cmd)))
                                    (run "Download GraalVM" "script/install-graalvm")
                                    (run "Build binary" "script/uberjar\nscript/compile" "30m")
                                    (run "Run tests" "script/test\nscript/run_lib_tests")
                                    (run "Release" ".circleci/script/release")
                                    {:persist_to_workspace {:root  "/tmp"
                                                            :paths ["release"]}}
                                    {:save_cache
                                     {:paths ["~/.m2" "~/graalvm-ce-java11-22.1.0"]
                                      :key   cache-key}}
                                    {:store_artifacts {:path        "/tmp/release"
                                                       :destination "release"}}
                                    (run "Publish artifact link to Slack"
                                         "./bb .circleci/script/publish_artifact.clj || true")])))))
(def config
  (let [docker-executor-conf  {:docker [{:image "circleci/clojure:openjdk-11-lein-2.9.8-bullseye"}]}
        machine-executor-conf {:machine {:image "ubuntu-2004:202111-01"}}
        mac-executor-conf     {:macos {:xcode "12.0.0"}}
        linux-graalvm-home    "/home/circleci/graalvm-ce-java11-22.1.0"
        mac-graalvm-home      "/Users/distiller/graalvm-ce-java11-22.1.0/Contents/Home"]
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
                   :jvm (jvm)
                   :linux (unix false false "x86_64" docker-executor-conf "large" linux-graalvm-home "linux")
                   :linux-static (unix true true "x86_64" docker-executor-conf "large" linux-graalvm-home "linux")
                   :linux-aarch64 (unix false
                                        false
                                        "aarch64"
                                        machine-executor-conf
                                        "arm.large"
                                        linux-graalvm-home
                                        "linux")
                   :linux-aarch64-static
                   (unix true true "aarch64" machine-executor-conf "arm.large" linux-graalvm-home "linux")
                   :mac (unix false false "x86_64" mac-executor-conf "large" mac-graalvm-home "mac")
                   :deploy (deploy)
                   :docker (docker))
      :workflows (ordered-map
                   :version 2
                   :ci      {:jobs ["jvm"
                                    "linux"
                                    "linux-static"
                                    "mac"
                                    "linux-aarch64"
                                    "linux-aarch64-static"
                                    {:deploy {:filters  {:branches {:only "master"}}
                                              :requires ["jvm" "linux"]}}
                                    {:docker {:filters  {:branches {:only "master"}}
                                              :requires ["linux" "linux-static" "linux-aarch64"]}}]}))))

(comment
  (spit "foo.yml"
        (yaml/generate-string config
                              :dumper-options
                              {:flow-style :block})))
