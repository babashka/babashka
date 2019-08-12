(defproject borkdude/babashka
  #=(clojure.string/trim
     #=(slurp "resources/BABASHKA_VERSION"))
  :description "babashka"
  :url "https://github.com/borkdude/babashka"
  :scm {:name "git"
        :url "https://github.com/borkdude/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [borkdude/sci "0.0.2"]]
  :profiles {:clojure-1.9.0 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :clojure-1.10.1 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :test {:dependencies [[clj-commons/conch "0.9.2"]]}
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
