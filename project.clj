(defproject borkdude/babashka
  #=(clojure.string/trim
     #=(slurp "resources/BABASHKA_VERSION"))
  :description "babashka"
  :url "https://github.com/borkdude/babashka"
  :scm {:name "git"
        :url "https://github.com/borkdude/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src" "sci/src" "babashka.curl/src"]
  :resource-paths ["resources" "sci/resources"]
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [org.clojure/tools.reader "1.3.2"]
                 [borkdude/edamame "0.0.10"]
                 [borkdude/graal.locking "0.0.2"]
                 [borkdude/sci.impl.reflector "0.0.1"]
                 [org.clojure/core.async "1.0.567"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/data.csv "1.0.0"]
                 [cheshire "5.10.0"]
                 [fipp "0.6.22"]
                 [com.cognitect/transit-clj "1.0.324"]
                 #_[nrepl/bencode "1.0.0"]]
  :profiles {:test {:dependencies [[clj-commons/conch "0.9.2"]
                                   [com.clojure-goes-fast/clj-async-profiler "0.4.0"]]}
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :main babashka.main
                       :aot :all}
             :reflection {:main babashka.impl.classes/generate-reflection-file}}
  :aliases {"bb" ["run" "-m" "babashka.main"]}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
