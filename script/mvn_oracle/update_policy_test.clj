#!/usr/bin/env bb
;; Metadata update policies and transfer status tests for Maven Resolver 1.9.27 compatibility.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/update_policy_test.clj
(ns update-policy-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.metadata :as metadata]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server]))

(def dir (fs/create-temp-dir))

(defn- stale?
  [millis policy]
  (#'metadata/stale? millis {:update policy}))

(defn- now [] (System/currentTimeMillis))
(def local-midnight (#'metadata/local-midnight-millis))

(deftest update-policy-test
  (testing "testIsUpdateRequiredPolicyNever"
    (is (not (stale? 0 :never)))
    (is (not (stale? (- (now) 604800000) :never))))
  (testing "testIsUpdateRequiredPolicyAlways"
    (is (stale? (now) :always))
    (is (stale? (- (now) 1000) :always)))
  (testing "testIsUpdateRequiredPolicyDaily"
    (is (stale? 0 :daily))
    (is (not (stale? (now) :daily)))
    (is (not (stale? local-midnight :daily)))
    (is (not (stale? (+ local-midnight 1000) :daily)))
    (is (stale? (- local-midnight 1000) :daily)))
  (testing "testIsUpdateRequiredPolicyInterval"
    (is (stale? 0 5))
    (is (not (stale? (now) 5)))
    (is (not (stale? (- (now) 5000) 5)))
    (is (stale? (- (now) (* 1000 60 5) 1000) 5))))

(def down {:id "central" :url "https://127.0.0.1:1/" :display-url "https://127.0.0.1:1/"})

(defn- required?
  ([file policy] (required? file policy 0))
  ([file policy local-updated]
   (session/with-session
     (#'metadata/update-required? file down {:update policy} local-updated))))

(deftest update-check-test
  (let [file (fs/file dir "g/a/1.0-SNAPSHOT/maven-metadata-central.xml")
        status (fs/file dir "g/a/1.0-SNAPSHOT/resolver-status.properties")]
    (fs/create-dirs (fs/parent file))
    (testing "missing metadata requires an update even under :never"
      (is (required? file :never)))
    (testing "fresh installed metadata prevents a remote update"
      (is (not (required? file :daily (now))))
      (is (required? file :daily (- local-midnight 1000)))
      (is (required? file :always (now))))
    (testing "an unknown update time requires a daily update but respects :never"
      (spit file "<metadata/>")
      (is (required? file :daily))
      (is (not (required? file :never))))
    (testing "a successful transfer records the update time and prevents another daily update"
      (#'metadata/touch! file down nil)
      (is (re-find #"(?m)^maven-metadata-central\.xml\.lastUpdated=\d+$" (slurp status)))
      (is (not (required? file :daily)))
      (is (required? file :always)))
    (testing "a recorded transfer failure prevents a daily retry only if cached metadata exists"
      (#'metadata/touch! file down "Connection refused")
      (is (= "Connection refused" (.getProperty (#'metadata/load-properties (slurp status)) "maven-metadata-central.xml.error")))
      (is (not (required? file :daily)))
      (fs/delete file)
      (is (required? file :daily)))
    (testing "missing remote metadata requires another update"
      (#'metadata/touch! file down "")
      (is (= "" (.getProperty (#'metadata/load-properties (slurp status)) "maven-metadata-central.xml.error")))
      (is (required? file :daily)))
    (testing "transfer status keys match Aether's authentication digest"
      (spit file "<metadata/>")
      (spit status (str "maven-metadata-central.xml.error=Connection refused\n"
                        "maven-metadata-central.xml/1e3667668bcdb79f50512e584a207d92f9be6324@default-central-https\\://127.0.0.1\\:1/.lastUpdated=" (now) "\n"))
      (is (not (session/with-session
                 (#'metadata/update-required? file (assoc down :credentials {:username "user" :password "secret"}) {:update :daily} 0))))
      (is (required? file :daily)))
    (testing "one session checks metadata once, even under :always"
      (session/with-session
        (is (#'metadata/update-required? file down {:update :always} 0))
        (#'metadata/touch! file down nil)
        (is (not (#'metadata/update-required? file down {:update :always} 0))))
      (is (required? file :always)))
    (testing "one session requests missing remote metadata once"
      (fs/delete file)
      (session/with-session
        (#'metadata/touch! file down "")
        (is (not (#'metadata/update-required? file down {:update :daily} 0))))
      (is (required? file :daily)))))

(deftest auth-digest-test
  (testing "the digest covers each credential of a settings.xml server"
    (is (= "" (#'metadata/auth-digest nil)))
    (is (= "" (#'metadata/auth-digest {})))
    (is (= "1e3667668bcdb79f50512e584a207d92f9be6324" (#'metadata/auth-digest {:username "user" :password "secret"})))
    (is (= "481da08763e03874e52fd77affc26fd4129a106a" (#'metadata/auth-digest {:username "u" :password "p"})))
    (is (= "e09b6700aaf463bbab142cc3ee04288291bb4495" (#'metadata/auth-digest {:username "u"})))
    (is (= "c1df73bc231a142ccc4e6fc4828df0a2a2dc9dd3" (#'metadata/auth-digest {:password "p"})))
    (is (= "b5daacb2a17f8ca7cc9502505108c93c9132556b" (#'metadata/auth-digest {:private-key "/k" :passphrase "pp"})))
    (is (= "d8179bf776c541ceb385713c6f74d2cae751f256" (#'metadata/auth-digest {:username "u" :password "p" :private-key "/k" :passphrase "pp"})))))

(deftest missing-and-unreadable-metadata-test
  (let [local (str (fs/file dir "local"))
        remote (fs/file dir "remote")
        repo {:id "test" :url (str (.toURI remote)) :snapshots {:enabled true :update :always :checksum :warn} :releases {:enabled true :update :always :checksum :warn}}
        art {:group "g" :artifact "a" :version "1.0-SNAPSHOT" :extension "jar"}
        cached (fs/file local "g/a/1.0-SNAPSHOT/maven-metadata-test.xml")]
    (fs/create-dirs (fs/parent cached))
    (fs/create-dirs remote)
    (testing "missing remote metadata deletes the cached copy"
      (spit cached "<metadata/>")
      (is (= {:version "1.0-SNAPSHOT" :repo :none} (metadata/resolve-snapshot local [repo] art)))
      (is (not (fs/exists? cached))))
    (testing "invalid XML metadata is ignored"
      (fs/create-dirs (fs/file remote "g/a/1.0-SNAPSHOT"))
      (spit (fs/file remote "g/a/1.0-SNAPSHOT/maven-metadata.xml") "<metadata><versioning>")
      (spit (fs/file remote "g/a/maven-metadata.xml") "<metadata><versioning>")
      (spit (fs/file local "g/a/maven-metadata-local.xml") "not xml")
      (is (= {:version "1.0-SNAPSHOT" :repo :none} (metadata/resolve-snapshot local [repo] art)))
      (is (= [] (:versions (metadata/versions local [repo] art)))))))

(deftest metadata-nature-test
  (let [local (str (fs/file dir "local-nature"))
        remote (fs/file dir "remote-nature")
        policy (fn [enabled update] {:enabled enabled :update update :checksum :warn})
        repo (fn [releases snapshots] {:id "test" :url (str (.toURI remote)) :releases releases :snapshots snapshots})
        art {:group "g" :artifact "a"}
        versions #(:versions (metadata/versions local [%1] art %2))]
    (fs/create-dirs (fs/file remote "g/a"))
    (spit (fs/file remote "g/a/maven-metadata.xml")
          "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>")
    (testing ":release skips a repository with releases disabled"
      (is (= [] (versions (repo (policy false :always) (policy true :always)) :release))))
    (testing ":release-or-snapshot uses a repository with only snapshots enabled"
      (is (= ["1.0"] (versions (repo (policy false :always) (policy true :always)) :release-or-snapshot))))
    (testing ":release-or-snapshot skips a repository with both policies disabled"
      (is (nil? (#'metadata/metadata-policy (repo (policy false :always) (policy false :always)) :release-or-snapshot))))
    (testing ":release-or-snapshot applies the more frequent update policy"
      (is (= :always (:update (#'metadata/metadata-policy (repo (policy true :never) (policy true :always)) :release-or-snapshot))))
      (is (= 5 (:update (#'metadata/metadata-policy (repo (policy true 5) (policy true :daily)) :release-or-snapshot))))
      (is (= :daily (:update (#'metadata/metadata-policy (repo (policy true :daily) (policy true :never)) :release-or-snapshot)))))
    (testing ":snapshot ignores the releases policy"
      (is (= :never (:update (#'metadata/metadata-policy (repo (policy true :always) (policy true :never)) :snapshot)))))
    (testing ":release-or-snapshot is the default"
      (is (= ["1.0"] (:versions (metadata/versions local [(repo (policy false :always) (policy true :always))] art)))))
    (testing ":release-or-snapshot applies the more lenient checksum policy"
      (is (= :warn (:checksum (#'metadata/metadata-policy (repo (assoc (policy true :always) :checksum :fail) (policy true :always)) :release-or-snapshot)))))))

(deftest metadata-checksum-test
  (let [local (str (fs/file dir "local-checksum"))
        remote (fs/file dir "remote-checksum")
        policy (fn [checksum] {:enabled true :update :always :checksum checksum})
        repo (fn [checksum] {:id "test" :url (str (.toURI remote)) :releases (policy checksum) :snapshots (policy checksum)})
        art {:group "g" :artifact "a"}
        cached (fs/file local "g/a/maven-metadata-test.xml")
        versions (fn [checksum]
                   (let [err (java.io.StringWriter.)]
                     [(session/with-session
                        (binding [*err* err]
                          (:versions (metadata/versions local [(repo checksum)] art))))
                      (str err)]))]
    (fs/create-dirs (fs/file remote "g/a"))
    (spit (fs/file remote "g/a/maven-metadata.xml")
          "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>")
    (spit (fs/file remote "g/a/maven-metadata.xml.sha1") "0000000000000000000000000000000000000000")
    (testing ":fail rejects metadata with a wrong checksum and records the failure"
      (let [[found _] (versions :fail)]
        (is (= [] found))
        (is (not (fs/exists? cached)))
        (is (str/includes? (.getProperty (#'metadata/load-properties (slurp (fs/file local "g/a/resolver-status.properties")))
                                         "maven-metadata-test.xml.error")
                           "Checksum validation failed"))))
    (testing ":warn reports a wrong checksum and uses the metadata"
      (let [[found err] (versions :warn)]
        (is (= ["1.0"] found))
        (is (str/includes? err "Checksum validation failed for g/a/maven-metadata.xml"))
        (is (fs/exists? cached))))
    (testing ":ignore uses the metadata without a report"
      (let [[found err] (versions :ignore)]
        (is (= ["1.0"] found))
        (is (not (str/includes? err "Checksum")))))))

(deftest server-error-test
  (let [local (str (fs/file dir "local-500"))
        stop (server/run-server (fn [_] {:status 500 :body "down"}) {:port 0 :legacy-return-value? false})
        url (str "http://localhost:" (server/server-port stop) "/")
        policy {:enabled true :update :always :checksum :warn}
        repo {:id "broken" :url url :display-url url :snapshots policy :releases policy}
        art {:group "g" :artifact "a" :version "1.0-SNAPSHOT" :extension "jar"}
        version-dir (fs/file local "g/a/1.0-SNAPSHOT")]
    (try
      (fs/create-dirs version-dir)
      (testing "HTTP 500 preserves and uses cached metadata"
        (spit (fs/file version-dir "maven-metadata-broken.xml")
              "<metadata><versioning><snapshot><timestamp>20240101.000000</timestamp><buildNumber>1</buildNumber></snapshot><lastUpdated>20240101000000</lastUpdated></versioning></metadata>")
        (is (= "1.0-20240101.000000-1" (:version (metadata/resolve-snapshot local [repo] art))))
        (is (fs/exists? (fs/file version-dir "maven-metadata-broken.xml"))))
      (testing "transfer status records HTTP 500"
        (is (str/includes? (.getProperty (#'metadata/load-properties (slurp (fs/file version-dir "resolver-status.properties")))
                                         "maven-metadata-broken.xml.error")
                           "HTTP 500")))
      (testing "local metadata supplies versions after HTTP 500"
        (spit (fs/file local "g/a/maven-metadata-local.xml")
              "<metadata><versioning><versions><version>1.0-SNAPSHOT</version></versions></versioning></metadata>")
        (is (= ["1.0-SNAPSHOT"] (:versions (metadata/versions local [repo] art)))))
      (finally
        (server/server-stop! stop)))))

(let [{:keys [fail error]} (t/run-tests 'update-policy-test)]
  (fs/delete-tree dir)
  (System/exit (if (zero? (+ fail error)) 0 1)))
