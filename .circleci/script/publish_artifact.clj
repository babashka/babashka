(require '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io]
         '[cheshire.core :refer [generate-string]]
         '[clojure.string :as str])

(def channel "#babashka_circleci_builds")
#_(def channel "#_test")
(def babashka-version (str/trim (slurp (io/file "resources" "BABASHKA_VERSION"))))

(def text (format "[%s - %s@%s]: https://%s-201467090-gh.circle-artifacts.com/0/release/babashka-%s-%s-amd64.zip"
                  (System/getenv "BABASHKA_PLATFORM")
                  (System/getenv "CIRCLE_BRANCH")
                  (System/getenv "CIRCLE_SHA1")
                  (System/getenv "CIRCLE_BUILD_NUM")
                  babashka-version
                  (System/getenv "BABASHKA_PLATFORM")))

(def slack-hook-url (System/getenv "SLACK_HOOK_URL"))
(when slack-hook-url
  (let [json (generate-string {:username "borkdude"
                               :channel channel
                               :text text})]
    (sh "curl" "-X" "POST" "-H" "Content-Type: application/json" "-d" json slack-hook-url)))
