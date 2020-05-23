(defproject borkdude/babashka
  #=(clojure.string/trim
     #=(slurp "resources/BABASHKA_VERSION"))
  :description "babashka"
  :url "https://github.com/borkdude/babashka"
  :scm {:name "git"
        :url "https://github.com/borkdude/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src" "sci/src" "babashka.curl/src" "babashka.pods/src"
                 "babashka.nrepl/src"]
  ;; for debugging Reflector.java code:
  ;; :java-source-paths ["sci/reflector/src-java"]
  :java-source-paths ["src-java"]
  :resource-paths ["resources" "sci/resources"]
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [org.clojure/tools.reader "1.3.2"]
                 [borkdude/edamame "0.0.11-alpha.12"]
                 [borkdude/graal.locking "0.0.2"]
                 [org.clojure/tools.cli "1.0.194"]
                 [cheshire "5.10.0"]
                 [fipp "0.6.22"]
                 [nrepl/bencode "1.1.0"]
                 [borkdude/sci.impl.reflector "0.0.1-java11"]]
  :profiles {:feature/xml  {:source-paths ["feature-xml"]
                            :dependencies [[org.clojure/data.xml "0.2.0-alpha6"]]}
             :feature/yaml {:source-paths ["feature-yaml"]
                            :dependencies [[clj-commons/clj-yaml "0.7.1"]]}
             :feature/jdbc {:source-paths ["feature-jdbc"]
                            :dependencies [[seancorfield/next.jdbc "1.0.424"]]}
             :feature/postgresql [:feature/jdbc {:dependencies [[org.postgresql/postgresql "42.2.12"]]}]
             :feature/hsqldb [:feature/jdbc {:dependencies [[org.hsqldb/hsqldb "2.4.0"]]}]
             :feature/core-async {:source-paths ["feature-core-async"]
                                  :dependencies [[org.clojure/core.async "1.1.587"]]}
             :feature/csv {:source-paths ["feature-csv"]
                           :dependencies [[org.clojure/data.csv "1.0.0"]]}
             :feature/transit {:source-paths ["feature-transit"]
                               :dependencies [[com.cognitect/transit-clj "1.0.324"]]}
             :feature/datascript {:source-paths ["feature-datascript"]
                                  :dependencies [[datascript "0.18.11"]]}
             :test [:feature/xml
                    :feature/yaml
                    :feature/postgresql
                    :feature/hsqldb
                    :feature/core-async
                    :feature/csv
                    :feature/transit
                    :feature/datascript
                    {:dependencies [[clj-commons/conch "0.9.2"]
                                    [com.clojure-goes-fast/clj-async-profiler "0.4.1"]
                                    [com.opentable.components/otj-pg-embedded "0.13.3"]]}]
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :main babashka.main
                       :aot :all}
             :native-image {:dependencies [[borkdude/clj-reflector-graal-java11-fix "0.0.1-graalvm-20.1.0"]]}
             :reflection {:main babashka.impl.classes/generate-reflection-file}}
  :aliases {"bb" ["with-profile" "test"  "run" "-m" "babashka.main"]}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
