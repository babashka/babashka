(ns babashka.impl.features
  {:no-doc true})

;; included by default
(def yaml?       (not= "false" (System/getenv "BABASHKA_FEATURE_YAML")))
(def xml?        (not= "false" (System/getenv "BABASHKA_FEATURE_XML")))
(def core-async? (not= "false" (System/getenv "BABASHKA_FEATURE_CORE_ASYNC")))
(def csv?        (not= "false" (System/getenv "BABASHKA_FEATURE_CSV")))
(def transit?    (not= "false" (System/getenv "BABASHKA_FEATURE_TRANSIT")))
(def java-time?  (not= "false" (System/getenv "BABASHKA_FEATURE_JAVA_TIME")))
(def java-nio?   (not= "false" (System/getenv "BABASHKA_FEATURE_JAVA_NIO")))

;; excluded by default
(def jdbc? (= "true" (System/getenv "BABASHKA_FEATURE_JDBC")))
(def postgresql? (= "true" (System/getenv "BABASHKA_FEATURE_POSTGRESQL")))
(def hsqldb? (= "true" (System/getenv "BABASHKA_FEATURE_HSQLDB")))
(def datascript? (= "true" (System/getenv "BABASHKA_FEATURE_DATASCRIPT")))
