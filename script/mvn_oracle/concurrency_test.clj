#!/usr/bin/env bb
;; Concurrent writes to the local repository from threads and processes.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/concurrency_test.clj
(ns concurrency-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.impl.mvn.repo :as repo]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
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
      (is (= threads (count (re-seq #"(?m)^a-\d+\.jar>central=$" (slurp (fs/file dir "_remote.repositories")))))))
    (testing "every snapshot line written at once ends up in _babashka.snapshots"
      (run-together threads #(#'repo/record-snapshot! (str dir) (str "b-" % "-SNAPSHOT.jar") (str "b-" % "-20260101.000000-1.jar")))
      (is (= threads (count (str/split-lines (slurp (fs/file dir "_babashka.snapshots")))))))))

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

(let [{:keys [fail error]} (t/run-tests 'concurrency-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
