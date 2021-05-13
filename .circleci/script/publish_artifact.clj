(require '[babashka.curl :as curl]
         '[cheshire.core :refer [generate-string]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def channel "#babashka-circleci-builds")
#_(def channel "#_test")
(def babashka-version (str/trim (slurp (io/file "resources" "BABASHKA_VERSION"))))
(def slack-hook-url (System/getenv "SLACK_HOOK_URL"))

(defn slack! [text]
  (when slack-hook-url
    (let [json (generate-string {:username "borkdude"
                                 :channel channel
                                 :text text})]
      (curl/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json}))))

(def platform
  (str (System/getenv "BABASHKA_PLATFORM")
       "-"
       (or (System/getenv "BABASHKA_ARCH") "amd64")
       (when (= "true" (System/getenv "BABASHKA_STATIC"))
         "-static")))

(def release-text (format "[%s - %s@%s]: https://%s-201467090-gh.circle-artifacts.com/0/release/babashka-%s-%s.tar.gz"
                          platform
                          (System/getenv "CIRCLE_BRANCH")
                          (System/getenv "CIRCLE_SHA1")
                          (System/getenv "CIRCLE_BUILD_NUM")
                          babashka-version
                          platform))

(slack! release-text)

(def binary-size-text
  (format "[%s - %s@%s] binary size: %s"
          platform
          (System/getenv "CIRCLE_BRANCH")
          (System/getenv "CIRCLE_SHA1")
          (slurp (io/file "/tmp/bb_size/size"))))

(slack! binary-size-text)
