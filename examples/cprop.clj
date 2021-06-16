#!/usr/bin/env bb
(require '[babashka.classpath :refer [add-classpath]])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.pprint :refer [pprint]])

(def deps '{:deps {cprop {:mvn/version "0.1.17"}}})
(def cp (:out (sh "clojure" "-Spath" "-Sdeps" (str deps))))
(add-classpath cp)

(require '[cprop.core :refer [load-config]])
(require '[cprop.source :refer [from-props-file]])

;; Load sample configuration from the file system
(def conf (load-config :file "cprop.edn"))

;; Print the configuration we just read in
(pprint conf)

;;=>
#_{:datomic {:url "CHANGE ME"}
   :aws {:access-key "AND ME"
         :secret-key "ME TOO"
         :region "FILL ME IN AS WELL"
         :visiblity-timeout-sec 30
         :max-conn 50
         :queue "cprop-dev"}
   :io {:http {:pool {:socket-timeout 600000
                      :conn-timeout :I-SHOULD-BE-A-NUMBER
                      :conn-req-timeout 600000
                      :max-total 200
                      :max-per-route :ME-ALSO}}}
   :other-things ["I am a vector and also like to place the substitute game"]}
(let [conf (load-config
            :file "cprop.edn"
            :merge [(from-props-file "cprop-override.properties")])]
  (pprint conf))

;;=>
#_{:datomic
   {:url "datomic:sql://?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic"},
   :aws
   {:access-key "super secret key",
    :secret-key "super secret s3cr3t!!!",
    :region "us-east-2",
    :visiblity-timeout-sec 30,
    :max-conn 50,
    :queue "cprop-dev"},
   :io
   {:http
    {:pool
     {:socket-timeout 600000,
      :conn-timeout 42,
      :conn-req-timeout 600000,
      :max-total 200,
      :max-per-route 42}}},
   :other-things ["1" "2" "3" "4" "5" "6" "7"]}
