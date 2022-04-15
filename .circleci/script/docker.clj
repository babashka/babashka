(require '[clojure.string :as s]
         '[babashka.process :as proc]
         '[babashka.fs :as fs])
(import '[java.time Instant])

(defn read-env
  ([k]
   (read-env k nil))
  ([k default]
   (or (System/getenv k)
       default)))

(def image-name "babashka/babashka")

(def image-tag (slurp "resources/BABASHKA_VERSION"))

(def latest-tag "latest")

(def platforms (read-env "PLATFORM" "linux/amd64"))

(def circle-repository-url (read-env "CIRCLE_REPOSITORY_URL"))

(def label-args
  ["--label" "'org.opencontainers.image.description=Native, fast starting Clojure interpreter for scripting'"
   "--label" "org.opencontainers.image.title=Babashka"
   "--label" (str "org.opencontainers.image.created=" (Instant/now))
   "--label" (str "org.opencontainers.image.url=" circle-repository-url)
   "--label" (str "org.opencontainers.image.documentation=" circle-repository-url)
   "--label" (str "org.opencontainers.image.source=" circle-repository-url)
   "--label" (str "org.opencontainers.image.revision=" (read-env "CIRCLE_SHA1"))
   "--label"
   (format "org.opencontainers.image.ref.name=%s:%s"
           (read-env "CIRCLE_TAG")
           (read-env "CIRCLE_BRANCH"))
   "--label" (str "org.opencontainers.image.version=" image-tag)])

(def snapshot (s/includes? image-tag "SNAPSHOT"))

(defn exec
  [cmd]
  (-> cmd
      (proc/process {:out :inherit :err :inherit})
      (proc/check)))

(defn docker-login
  [username password]
  (exec ["docker" "login" "-u" username "-p" password]))

(defn build-push
  [image-tag platform docker-file]
  (println (format "Building and pushing %s Docker image(s) %s:%s"
                   platform
                   image-name
                   image-tag))
  (let [base-cmd ["docker" "buildx" "build"
                  "-t" (str image-name ":" image-tag)
                  "--platform" platform
                  "--push"
                  "-f" docker-file]]
    (exec (concat base-cmd label-args ["."]))))

(defn build-push-images
  []
  (doseq [platform (s/split platforms #",")]
    (let [tarball-platform (s/replace platform #"\/" "-")
          tarball-platform (if (= "linux-arm64")
                             "linux-aarch64"
                             tarball-platform)
          tarball-path     (format "/tmp/release/babashka-%s-%s.tar.gz"
                                   image-tag
                                   tarball-platform)]
      (fs/create-dirs platform)
      (exec ["tar" "zxvf" tarball-path "-C" platform])
      ; this overwrites, but this is to work around having built the uberjar/metabom multiple times
      (fs/copy (format "/tmp/release/%s-metabom.jar" tarball-platform) "metabom.jar" {:replace-existing true}))
    (build-push image-tag platform "Dockerfile.ci")
    (when-not snapshot
      (build-push latest-tag platform "Dockerfile.ci"))))

(defn build-push-alpine-images
  "Build alpine image for linux-amd64 only (no upstream arm64 support yet)"
  []
  (exec ["tar" "zxvf" (str "/tmp/release/babashka-" image-tag "-linux-amd64-static.tar.gz")])
  (build-push (str image-tag "-alpine") "linux/amd64" "Dockerfile.alpine")
  (when-not snapshot
    (build-push "alpine" "linux/amd64" "Dockerfile.alpine")))

(when (= *file* (System/getProperty "babashka.file"))
  (if snapshot
    (println "This is a snapshot version")
    (println "This is a non-snapshot version"))
  (docker-login (read-env "DOCKERHUB_USER") (read-env "DOCKERHUB_PASS"))
  (build-push-images)
  (build-push-alpine-images))
