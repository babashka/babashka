#!/usr/bin/env bb
;; Concurrent writes to the local repository from threads and processes.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/concurrency_test.clj
(ns concurrency-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.impl.mvn.http :as http]
            [babashka.impl.mvn.repo :as repo]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server]))

(def ^:private threads 64)

(defn- run-together
  "Calls (f i) for every i below n, each on its own thread, all released at once."
  [n f]
  (let [start (promise)
        futures (mapv (fn [i] (future @start (f i))) (range n))]
    (deliver start true)
    (run! deref futures)))

(defn- bb-processes
  "Starts n bb processes, each evaluating (code i), with this process's
  classpath. Returns their results."
  [n code]
  (let [bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb")))
        classpath (cp/get-classpath)]
    (->> (range n)
         (mapv (fn [i] (p/process (cond-> [bb] classpath (conj "-cp" classpath) true (conj "-e" (code i)))
                                  {:out :string :err :string})))
         (mapv deref))))

(deftest tracking-files-test
  (fs/with-temp-dir [dir {}]
    (testing "every repository line written at once ends up in _remote.repositories"
      (run-together threads #(#'repo/record-remote! (str dir) (str "a-" % ".jar") "central"))
      (is (= threads (count (re-seq #"(?m)^a-\d+\.jar>central=$" (slurp (fs/file dir "_remote.repositories")))))))))

(def ^:private snapshot-metadata
  "<metadata modelVersion=\"1.1.0\"><groupId>g</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version><versioning>
  <snapshot><timestamp>20260101.000000</timestamp><buildNumber>1</buildNumber></snapshot><lastUpdated>20260101000000</lastUpdated>
  <snapshotVersions><snapshotVersion><extension>jar</extension><value>1.0-20260101.000000-1</value><updated>20260101000000</updated></snapshotVersion></snapshotVersions>
  </versioning></metadata>")

(deftest build-copies-test
  (testing "threads resolving one -SNAPSHOT at once leave an intact copy with the build's time"
    (fs/with-temp-dir [dir {}]
      (let [remote-dir (fs/file dir "remote" "g" "a" "1.0-SNAPSHOT")
            local-dir (fs/file dir "local" "g" "a" "1.0-SNAPSHOT")
            content (str/join (repeat 100000 "x"))
            remote (repo/remote-repo {} ["r" {:url (str (.toURI (fs/file dir "remote")))
                                              :snapshots {:checksum :ignore}}])
            artifact {:group "g" :artifact "a" :version "1.0-SNAPSHOT" :extension "jar"}]
        (fs/create-dirs remote-dir)
        (spit (fs/file remote-dir "maven-metadata.xml") snapshot-metadata)
        (spit (fs/file remote-dir "a-1.0-20260101.000000-1.jar") content)
        (run-together threads (fn [_] (repo/resolve-file! (str (fs/file dir "local")) [remote] artifact)))
        (is (= content (slurp (fs/file local-dir "a-1.0-SNAPSHOT.jar"))))
        (is (= (.lastModified (fs/file local-dir "a-1.0-20260101.000000-1.jar"))
               (.lastModified (fs/file local-dir "a-1.0-SNAPSHOT.jar"))))
        (is (empty? (fs/glob local-dir "*.tmp")))))))

(deftest tracking-files-across-processes-test
  (testing "repository lines several processes write at once all end up in _remote.repositories"
    (fs/with-temp-dir [dir {}]
      (let [per-process 64
            results (bb-processes 4 (fn [i]
                                      (pr-str `(do (require 'babashka.impl.mvn.repo)
                                                   (dotimes [j# ~per-process]
                                                     ((var babashka.impl.mvn.repo/record-remote!)
                                                      ~(str dir) (str "p" ~i "-" j# ".jar") "central"))))))]
        (doseq [{:keys [exit err]} results]
          (is (zero? exit) err))
        (is (= (* 4 per-process)
               (count (re-seq #"(?m)^p\d+-\d+\.jar>central=$" (slurp (fs/file dir "_remote.repositories"))))))))))

(def ^:private body-size (* 256 1024))

(defn- slow-handler
  "Streams body-size bytes in small chunks with pauses, so downloads overlap."
  [req]
  (server/as-channel req
    {:on-open (fn [ch]
                (future
                  (server/send! ch {:status 200 :headers {"Content-Type" "application/octet-stream"}} false)
                  (dotimes [_ 64]
                    (server/send! ch (byte-array (quot body-size 64) (byte 7)) false)
                    (Thread/sleep 5))
                  (server/close ch)))}))

(deftest concurrent-processes-test
  (testing "processes downloading one artifact into one local repository all succeed, the file intact"
    (fs/with-temp-dir [dir {}]
      (let [stop (server/run-server slow-handler {:port 0 :legacy-return-value? false})
            url (str "http://localhost:" (server/server-port stop) "/g/a/1/a-1.jar")
            dest (str (fs/file dir "local" "g" "a" "1" "a-1.jar"))]
        (try
          (let [results (bb-processes 4 (fn [_]
                                          (pr-str `(do (require '[babashka.impl.mvn.http :as ~'h])
                                                       (~'h/download! ~url ~dest {:checksum :ignore :label "a-1.jar" :repo-id "slow"})))))]
            (doseq [{:keys [exit err]} results]
              (is (zero? exit) (str err "\nfiles in " (fs/parent dest) ": "
                                    (mapv (comp str fs/file-name) (fs/list-dir (fs/parent dest))))))
            (is (= body-size (fs/size dest)))
            (is (empty? (fs/glob (fs/parent dest) "*.tmp"))))
          (finally
            (server/server-stop! stop)))))))

(deftest version-range-test
  (doseq [in-place [false true]]
    (testing (str "threads resolving one version range at once all get the highest version, in-place writes " in-place)
      (fs/with-temp-dir [dir {}]
        (let [versions (mapv #(str "1." %) (range 5000))
              remote-dir (fs/file dir "remote" "acme" "lib")
              config {:mvn/repos {"r" {:url (str (.toURI (fs/file dir "remote")))}}
                      :mvn/local-repo (str (fs/file dir "local"))}
              results (atom [])]
          (fs/create-dirs remote-dir)
          (spit (fs/file remote-dir "maven-metadata.xml")
                (str "<metadata><groupId>acme</groupId><artifactId>lib</artifactId><versioning><versions>"
                     (apply str (map #(str "<version>" % "</version>") versions))
                     "</versions></versioning></metadata>"))
          (with-redefs [http/windows? in-place]
            (binding [*err* (java.io.StringWriter.)]
              (session/with-session
                (run-together threads
                              (fn [_]
                                (swap! results conj
                                       (try (:mvn/version (second (ext/canonicalize 'acme/lib {:mvn/version "[1.0,)"} config)))
                                            (catch Exception e (ex-message e)))))))))
          (is (= #{"1.4999"} (set @results))))))))

(let [{:keys [fail error]} (t/run-tests 'concurrency-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
