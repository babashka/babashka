(defproject babashka/babashka
  #=(clojure.string/trim
     #=(slurp "resources/BABASHKA_VERSION"))
  :description "babashka"
  :url "https://github.com/babashka/babashka"
  :scm {:name "git"
        :url "https://github.com/babashka/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src" "sci/src" "babashka.curl/src" "fs/src" "pods/src"
                 "babashka.core/src"
                 "babashka.nrepl/src" "depstar/src" "process/src"
                 "deps.clj/src" "deps.clj/resources"
                 "impl-java/src"]
  ;; for debugging Reflector.java code:
  ;; :java-source-paths ["sci/reflector/src-java"]
  :java-source-paths ["src-java"]
  :resource-paths ["resources" "sci/resources"]
  :test-selectors {:default (complement :windows-only)
                   :windows (complement :skip-windows)
                   :non-flaky (complement :flaky)}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [borkdude/edamame "1.1.17"]
                 [borkdude/graal.locking "0.0.2"]
                 [org.clojure/tools.cli "1.0.214"]
                 [cheshire "5.11.0"]
                 [nrepl/bencode "1.1.0"]
                 [borkdude/sci.impl.reflector "0.0.1"]
                 [org.babashka/sci.impl.types "0.0.2"]
                 [org.babashka/babashka.impl.java "0.1.6"]
                 [org.clojure/core.async "1.6.673"]
                 [org.clojure/test.check "1.1.1"]
                 [com.github.clj-easy/graal-build-time "0.1.0"]
                 [rewrite-clj/rewrite-clj "1.1.46"]
                 [insn/insn "0.5.2"]
                 [org.babashka/cli "0.6.45"]
                 [org.babashka/http-client "0.0.3"]]
  :plugins       [[org.kipz/lein-meta-bom "0.1.1"]]
  :metabom {:jar-name "metabom.jar"}
  :profiles {:feature/xml  {:source-paths ["feature-xml"]
                            :dependencies [[org.clojure/data.xml "0.2.0-alpha8"]]}
             :feature/yaml {:source-paths ["feature-yaml"]
                            :dependencies [[clj-commons/clj-yaml "0.7.169"
                                            #_#_clj-commons/clj-yaml "0.7.110"]]}
             :feature/jdbc {:source-paths ["feature-jdbc"]
                            :dependencies [[seancorfield/next.jdbc "1.1.610"]]}
             :feature/sqlite [:feature/jdbc {:dependencies [[org.xerial/sqlite-jdbc "3.36.0.3"]]}]
             :feature/postgresql [:feature/jdbc {:dependencies [[org.postgresql/postgresql "42.2.18"]]}]
             ;:feature/oracledb [:feature/jdbc {:dependencies [[com.oracle.database.jdbc/ojdbc8 "19.8.0.0"]]}]
             :feature/oracledb [:feature/jdbc {:dependencies [[io.helidon.integrations.db/ojdbc "2.1.0"]]}] ; ojdbc10 + GraalVM config, by Oracle
             :feature/hsqldb [:feature/jdbc {:dependencies [[org.hsqldb/hsqldb "2.5.1"]]}]
             :feature/csv {:source-paths ["feature-csv"]
                           :dependencies [[org.clojure/data.csv "1.0.0"]]}
             :feature/transit {:source-paths ["feature-transit"]
                               :dependencies [[com.cognitect/transit-clj "1.0.329"]]}
             :feature/datascript {:source-paths ["feature-datascript"]
                                  :dependencies [[datascript "1.3.10"]]}
             :feature/httpkit-client {:source-paths ["feature-httpkit-client"]
                                      :dependencies [[http-kit "2.6.0-RC1"]]}
             :feature/httpkit-server {:source-paths ["feature-httpkit-server"]
                                      :dependencies [[http-kit "2.6.0-RC1"]]}
             :feature/lanterna {:source-paths ["feature-lanterna"]
                                :dependencies [[babashka/clojure-lanterna "0.9.8-SNAPSHOT"]]}
             :feature/core-match {:source-paths ["feature-core-match"]
                                  :dependencies [[org.clojure/core.match "1.0.0"]]}
             :feature/hiccup {:source-paths ["feature-hiccup"]
                              :dependencies [[hiccup/hiccup "2.0.0-alpha2"]]}
             :feature/test-check {:source-paths ["feature-test-check"]}
             :feature/spec-alpha {:source-paths ["feature-spec-alpha"]}
             :feature/selmer {:source-paths ["feature-selmer"]
                              :dependencies [[selmer/selmer "1.12.50"]]}
             :feature/logging {:source-paths ["feature-logging"]
                               :dependencies [[com.taoensso/timbre "6.0.4"]
                                              [org.clojure/tools.logging "1.1.0"]]}
             :feature/priority-map {:source-paths ["feature-priority-map"]
                                    :dependencies [[org.clojure/data.priority-map "1.1.0"]]}
             :feature/rrb-vector {:source-paths ["feature-rrb-vector"]
                                  :dependencies [[org.clojure/core.rrb-vector "0.1.2"]]}
             :test [:feature/xml
                    :feature/lanterna
                    :feature/yaml
                    :feature/postgresql
                    :feature/hsqldb
                    :feature/csv
                    :feature/transit
                    :feature/datascript
                    :feature/httpkit-client
                    :feature/httpkit-server
                    :feature/core-match
                    :feature/hiccup
                    :feature/test-check
                    :feature/spec-alpha
                    :feature/selmer
                    :feature/logging
                    :feature/priority-map
                    :feature/rrb-vector
                    {:dependencies [[com.clojure-goes-fast/clj-async-profiler "0.5.0"]
                                    [com.opentable.components/otj-pg-embedded "0.13.3"]
                                    [nubank/matcher-combinators "3.6.0"]]}]
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"
                                  "-Dborkdude.dynaload.aot=true"]
                       :main babashka.main
                       :aot [babashka.main]}
             :reflection {:main babashka.impl.classes/generate-reflection-file}}
  :aliases {"bb" ["with-profile" "test"  "run" "-m" "babashka.main"]}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
