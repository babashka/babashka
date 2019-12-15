(defproject borkdude/babashka
  #=(clojure.string/trim
     #=(slurp "resources/BABASHKA_VERSION"))
  :description "babashka"
  :url "https://github.com/borkdude/babashka"
  :scm {:name "git"
        :url "https://github.com/borkdude/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src" "sci/src"]
  :resource-paths ["resources" "sci/resources"]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.reader "1.3.2"]
                 [borkdude/edamame "0.0.10-alpha.2"]
                 [org.clojure/core.async "0.4.500"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/data.csv "0.1.4"]
                 [cheshire "5.9.0"]]
  :profiles {:test {:dependencies [[clj-commons/conch "0.9.2"]]}
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :main babashka.main
                       :aot :all}}
  :aliases {"bb" ["run" "-m" "babashka.main"]}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
