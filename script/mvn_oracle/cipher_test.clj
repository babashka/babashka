#!/usr/bin/env bb
;; babashka.mvn.cipher against vectors made by plexus-cipher and
;; plexus-sec-dispatcher 2.0 on the JVM (cipher_vectors.clj).
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/cipher_test.clj
(ns cipher-test
  (:require [babashka.fs :as fs]
            [babashka.mvn.cipher :as cipher]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(def vectors (edn/read-string (slurp "script/mvn_oracle/cipher-vectors.edn")))

(defn- security-file
  "A settings-security.xml with the vectors' master blob, in dir."
  [dir]
  (let [f (fs/file dir "settings-security.xml")]
    (spit f (str "<settingsSecurity><master>" (:master-blob vectors) "</master></settingsSecurity>"))
    (str f)))

(deftest master-test
  (fs/with-temp-dir [dir {}]
    (is (= (:master vectors) (cipher/master-password (security-file dir))))))

(deftest relocation-test
  (fs/with-temp-dir [dir {}]
    (security-file dir)
    (let [f (fs/file dir "relocated.xml")]
      (spit f "<settingsSecurity><relocation>settings-security.xml</relocation></settingsSecurity>")
      (is (= (:master vectors) (cipher/master-password (str f)))))))

(deftest decrypt-password-test
  (fs/with-temp-dir [dir {}]
    (let [file (security-file dir)]
      (doseq [{:keys [plain blob dispatcher in-text]} (:cases vectors)]
        (testing (pr-str plain)
          (is (= dispatcher (cipher/decrypt-password blob {:file file})))
          (is (= in-text (cipher/decrypt-password (str "before " blob " after") {:file file})))))
      (testing "plain text passes through, as do escaped braces"
        (is (= (:not-encrypted vectors) (cipher/decrypt-password "plain-password" {:file file})))
        (is (= (:escaped-braces vectors) (cipher/decrypt-password "\\{not-a-blob\\}" {:file file})))
        (is (nil? (cipher/decrypt-password nil {:file file})))))))

(deftest errors-name-the-server-test
  (fs/with-temp-dir [dir {}]
    (let [blob (:blob (first (:cases vectors)))
          missing (str (fs/file dir "nope.xml"))
          no-master (str (fs/file dir "empty.xml"))]
      (spit no-master "<settingsSecurity/>")
      (is (thrown-with-msg? Exception #"password of server nexus: .*nope.xml does not exist"
                            (cipher/decrypt-password blob {:server "nexus" :file missing})))
      (is (thrown-with-msg? Exception #"password of server nexus: .*no master password"
                            (cipher/decrypt-password blob {:server "nexus" :file no-master})))
      (is (thrown-with-msg? Exception #"custom dispatchers"
                            (cipher/decrypt-password "{[type=foo]abc}" {:server "nexus" :file (security-file dir)})))
      (testing "a blob the master does not fit is an error, not a wrong password"
        (spit no-master (str "<settingsSecurity><master>{" (subs (:master-blob vectors) 1 20) "}</master></settingsSecurity>"))
        (is (thrown-with-msg? Exception #"password of server nexus"
                              (cipher/decrypt-password blob {:server "nexus" :file no-master})))))))

(let [{:keys [fail error]} (t/run-tests 'cipher-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
