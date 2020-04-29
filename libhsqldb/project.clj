(defproject org.babashka/libhsqldb "0.0.1-SNAPSHOT"
  :description "babashka"
  :url "https://github.com/borkdude/babashka"
  :scm {:name "git"
        :url "https://github.com/borkdude/babashka"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src" "../sci/src"]
  :resource-paths ["resources"]
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [borkdude/edamame "0.0.11-alpha.9"]
                 [borkdude/graal.locking "0.0.2"]
                 [borkdude/sci.impl.reflector "0.0.1"]
                 [org.hsqldb/hsqldb "2.4.0"]
                 [seancorfield/next.jdbc "1.0.424"]]
  :profiles {:uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :aot :all}}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
