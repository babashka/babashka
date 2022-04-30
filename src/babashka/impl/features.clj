(ns babashka.impl.features
  {:no-doc true})

;; included by default
(def yaml?           (not= "false" (System/getenv "BABASHKA_FEATURE_YAML")))
(def xml?            (not= "false" (System/getenv "BABASHKA_FEATURE_XML")))
(def csv?            (not= "false" (System/getenv "BABASHKA_FEATURE_CSV")))
(def transit?        (not= "false" (System/getenv "BABASHKA_FEATURE_TRANSIT")))
(def java-time?      (not= "false" (System/getenv "BABASHKA_FEATURE_JAVA_TIME")))
(def java-net-http?  (not= "false" (System/getenv "BABASHKA_FEATURE_JAVA_NET_HTTP")))
(def java-nio?       (not= "false" (System/getenv "BABASHKA_FEATURE_JAVA_NIO")))
(def httpkit-client? (not= "false" (System/getenv "BABASHKA_FEATURE_HTTPKIT_CLIENT")))
(def httpkit-server? (not= "false" (System/getenv "BABASHKA_FEATURE_HTTPKIT_SERVER")))
(def core-match?     (not= "false" (System/getenv "BABASHKA_FEATURE_CORE_MATCH")))
(def hiccup?         (not= "false" (System/getenv "BABASHKA_FEATURE_HICCUP")))
(def test-check?     (not= "false" (System/getenv "BABASHKA_FEATURE_TEST_CHECK")))
(def selmer?         (not= "false" (System/getenv "BABASHKA_FEATURE_SELMER")))
(def logging?        (not= "false" (System/getenv "BABASHKA_FEATURE_LOGGING")))
(def priority-map?   (not= "false" (System/getenv "BABASHKA_FEATURE_PRIORITY_MAP")))
(def rrb-vector?     (not= "false" (System/getenv "BABASHKA_FEATURE_RRB_VECTOR")))

;; excluded by default
(def jdbc? (= "true" (System/getenv "BABASHKA_FEATURE_JDBC")))
(def sqlite? (= "true" (System/getenv "BABASHKA_FEATURE_SQLITE")))
(def postgresql? (= "true" (System/getenv "BABASHKA_FEATURE_POSTGRESQL")))
(def oracledb? (= "true" (System/getenv "BABASHKA_FEATURE_ORACLEDB")))
(def hsqldb? (= "true" (System/getenv "BABASHKA_FEATURE_HSQLDB")))
(def datascript? (= "true" (System/getenv "BABASHKA_FEATURE_DATASCRIPT")))
(def lanterna? (= "true" (System/getenv "BABASHKA_FEATURE_LANTERNA")))
(def spec-alpha? (= "true" (System/getenv "BABASHKA_FEATURE_SPEC_ALPHA")))

(when xml?
  (require '[babashka.impl.xml]))

(when yaml?
  (require '[babashka.impl.yaml]
           '[babashka.impl.ordered]))

(when jdbc?
  (require '[babashka.impl.jdbc]))

(when csv?
  (require '[babashka.impl.csv]))

(when transit?
  (require '[babashka.impl.transit]))

(when datascript?
  (require '[babashka.impl.datascript]))

(when httpkit-client?
  (require '[babashka.impl.httpkit-client]))

(when httpkit-server?
  (require '[babashka.impl.httpkit-server]))

(when lanterna?
  (require '[babashka.impl.lanterna]))

(when core-match?
  (require '[babashka.impl.match]))

(when hiccup?
  (require '[babashka.impl.hiccup]))

(when test-check?
  (require '[babashka.impl.clojure.test.check]))

(when spec-alpha?
  (require '[babashka.impl.spec]))

(when selmer?
  (require '[babashka.impl.selmer]))

(when logging?
  (require '[babashka.impl.logging]))

(when priority-map?
  (require '[babashka.impl.priority-map]))

(when rrb-vector?
  (require '[babashka.impl.rrb-vector]))
