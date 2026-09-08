#!/usr/bin/env bb
;; Proxies: selection after Maven's DefaultProxySelector with the env
;; fallback deps.clj used to pass to java, and a resolve through a proxy
;; that demands Basic auth, for a repository host that does not resolve.
;; Run: CLOJURE_CLI_ALLOW_HTTP_REPO=true ./bb -cp resources/src/babashka script/mvn_oracle/proxy_test.clj
(ns proxy-test
  (:require [babashka.fs :as fs]
            [babashka.mvn.settings :as settings]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [org.httpkit.server :as server])
  (:import [java.util Base64]
           [java.util.zip ZipEntry ZipOutputStream]))

(def vectors (edn/read-string (slurp "script/mvn_oracle/cipher-vectors.edn")))

(defn- settings-with [proxies]
  (settings/parse
   (str "<settings><proxies>"
        (apply str (for [{:keys [id protocol host port active non-proxy-hosts username password]} proxies]
                     (str "<proxy><id>" id "</id><active>" (if (false? active) "false" "true") "</active>"
                          "<protocol>" protocol "</protocol><host>" host "</host><port>" port "</port>"
                          (when username (str "<username>" username "</username><password>" password "</password>"))
                          (when non-proxy-hosts (str "<nonProxyHosts>" non-proxy-hosts "</nonProxyHosts>"))
                          "</proxy>")))
        "</proxies></settings>")))

(deftest selection-test
  (let [s (settings-with [{:id "off" :protocol "https" :host "off.example.com" :port 1 :active false}
                          {:id "corp" :protocol "https" :host "proxy.example.com" :port 3128
                           :non-proxy-hosts "localhost|*.internal.example.com"
                           :username "u" :password "p"}
                          {:id "second" :protocol "https" :host "second.example.com" :port 2}
                          {:id "plain" :protocol "http" :host "plain.example.com" :port 8080}])]
    (testing "the first active proxy for the protocol, with its credentials"
      (is (= {:host "proxy.example.com" :port 3128 :username "u" :password "p"}
             (settings/proxy-for s "https://repo1.maven.org/maven2/"))))
    (testing "the protocol picks the proxy"
      (is (= {:host "plain.example.com" :port 8080}
             (settings/proxy-for s "http://repo.example.com/"))))
    (testing "nonProxyHosts means a direct connection, not the next proxy"
      (is (nil? (settings/proxy-for s "https://localhost:8080/")))
      (is (nil? (settings/proxy-for s "https://nexus.internal.example.com/")))
      (is (nil? (settings/proxy-for s "https://NEXUS.INTERNAL.EXAMPLE.COM/"))))
    (testing "file repositories and unknown protocols are direct"
      (is (nil? (settings/proxy-for s "file:///tmp/repo/")))
      (is (nil? (settings/proxy-for s "ftp://x/"))))))

(deftest env-fallback-test
  ;; The environment cannot be set from here, so only the shape of what
  ;; settings without proxies give: nil, or the env proxy when the test
  ;; runner has one.
  (let [s (settings-with [])
        env-proxy (System/getenv "https_proxy")]
    (if env-proxy
      (is (some? (settings/proxy-for s "https://repo1.maven.org/maven2/")))
      (is (nil? (settings/proxy-for s "https://repo1.maven.org/maven2/"))))))

;; End to end

(def user "proxyuser")
(def case-1 (first (:cases vectors)))

(defn- write-artifact! [root]
  (let [dir (fs/file root "proxied" "lib" "1.0.0")]
    (fs/create-dirs dir)
    (spit (fs/file dir "lib-1.0.0.pom")
          "<project><modelVersion>4.0.0</modelVersion><groupId>proxied</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>")
    (with-open [zip (ZipOutputStream. (io/output-stream (fs/file dir "lib-1.0.0.jar")))]
      (.putNextEntry zip (ZipEntry. "proxied/lib.clj"))
      (.write zip (.getBytes "(ns proxied.lib) (def answer 43)" "UTF-8"))
      (.closeEntry zip))))

(defn- expected-auth [password]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str user ":" password) "UTF-8"))))

(defn- proxy-handler
  "A forward proxy for one repository host: 407 without the right
  Proxy-Authorization, otherwise the file behind the absolute request URI."
  [root seen password]
  (fn [{:keys [uri headers]}]
    (swap! seen conj (get headers "proxy-authorization"))
    (cond
      (not= (get headers "proxy-authorization") (expected-auth password))
      {:status 407 :headers {"Proxy-Authenticate" "Basic realm=\"proxy\""} :body "auth"}

      (str/starts-with? uri "http://repo.invalid/")
      (let [f (fs/file root (subs uri (count "http://repo.invalid/")))]
        (if (fs/exists? f) {:status 200 :body (fs/file f)} {:status 404 :body "missing"}))

      :else {:status 502 :body "not my repository"})))

(deftest resolve-through-authenticated-proxy-test
  (fs/with-temp-dir [dir {}]
    (let [root (fs/file dir "repo")
          m2 (fs/file dir ".m2")
          seen (atom [])
          stop (server/run-server (proxy-handler root seen (:plain case-1)) {:port 0 :legacy-return-value? false})
          port (server/server-port stop)]
      (try
        (write-artifact! root)
        (fs/create-dirs m2)
        (spit (fs/file m2 "settings-security.xml")
              (str "<settingsSecurity><master>" (:master-blob vectors) "</master></settingsSecurity>"))
        (spit (fs/file m2 "settings.xml")
              (str "<settings><proxies><proxy><id>test</id><active>true</active><protocol>http</protocol>"
                   "<host>127.0.0.1</host><port>" port "</port>"
                   "<username>" user "</username><password>" (:blob case-1) "</password>"
                   "<nonProxyHosts>localhost</nonProxyHosts></proxy></proxies></settings>"))
        (System/setProperty "user.home" (str dir))
        (System/setProperty "settings.security" (str (fs/file m2 "settings-security.xml")))
        (require 'babashka.deps)
        ((resolve 'babashka.deps/add-deps)
         {:deps {'proxied/lib {:mvn/version "1.0.0"}}
          :mvn/repos {"proxied" {:url "http://repo.invalid/"}}
          :mvn/local-repo (str (fs/file dir "local-repo"))}
         {:force true :extra-env {"BABASHKA_DEPS_RESOLVER" "native"}})
        (require 'proxied.lib)
        (is (= 43 @(resolve 'proxied.lib/answer)))
        (testing "the proxy was challenged once and then saw the decrypted password"
          (is (some nil? @seen))
          (is (some #{(expected-auth (:plain case-1))} @seen)))
        (finally (server/server-stop! stop))))))

(let [{:keys [fail error]} (t/run-tests 'proxy-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
