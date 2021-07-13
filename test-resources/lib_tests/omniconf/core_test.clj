(ns omniconf.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [omniconf.core :as cfg])
  (:import (java.io File)))

(cfg/define
  {:conf        {:type :file}
   :foo         {:type     :string
                 :required true}
   :the-boolean {:type :boolean}
   :missing     {:type     :string
                 :required true}})

(deftest load-cfg-test
  (testing "multiple config sources"
    (let [temp-cfg-file (File/createTempFile "cfg" "edn")
          _             (.deleteOnExit temp-cfg-file)
          fake-args     ["--conf" (.getAbsolutePath temp-cfg-file)
                         "--foo" "this will be overridden"]]
      (do
        ; put some props in the temp file
        (spit temp-cfg-file "{:foo \"final value\" :the-boolean false }")
        ; and set a system property
        (System/setProperty "the-boolean" "18")
        (cfg/populate-from-cmd fake-args)
        (cfg/populate-from-file (cfg/get :conf))
        (cfg/populate-from-properties)
        ; cleanup
        (System/clearProperty "the-boolean")))
    (is (= "final value" (cfg/get :foo)))
    (is (= true (cfg/get :the-boolean)))
    (is (thrown-with-msg? Exception #":missing" (cfg/verify)))
    (cfg/populate-from-map {:missing "abc"})
    (let [verify-output (with-out-str (cfg/verify))]
      (is (every? #(str/includes? verify-output %) [":missing" "abc"])))))
