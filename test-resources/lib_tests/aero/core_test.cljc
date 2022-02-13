;; Copyright © 2015-2017, JUXT LTD.

(ns aero.core-test
  (:require
   [aero.core :refer [read-config reader
                      #?@(:clj [deferred resource-resolver root-resolver])
                      #?(:cljs Deferred)]
    #?@(:cljs [:refer-macros [deferred]])]
   [clojure.test :refer [deftest is testing are]]
   #?@(:clj [[clojure.java.io :as io]]
       :cljs [[goog.object :as gobj]
              [goog.string :as gstring]
              goog.string.format
              [cljs.tools.reader.reader-types
               :refer [source-logging-push-back-reader]]]))
  ;; BB-TEST-PATCH
  #_#?(:clj (:import [aero.core Deferred])))

(defn env [s]
  #?(:clj (System/getenv (str s)))
  #?(:cljs (gobj/get js/process.env s)))

(defn- string-reader
  [str]
  (#?(:cljs source-logging-push-back-reader
      :clj java.io.StringReader.) str))

(def network-call-count (atom 0))

(defmethod reader 'expensive-network-call
   [_ tag value]
   (deferred
     (swap! network-call-count inc)
     (inc value)))

(defmethod reader 'myflavor
  [opts tag value]
  (if (= value :favorite) :chocolate :vanilla))

(deftest basic-test
  ;; BB-TEST-PATCH: This and several other test files were changed to work with
  ;; our dir structure
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= "Hello World!" (:greeting config))))
  (testing "Reading empty config returns nil"
    (is (= nil (read-config "test-resources/lib_tests/aero/empty-config.edn")))))

(defn source [path]
  #?(:clj (io/reader path)
     :cljs path))

(deftest hostname-test
  (is (= {:color "green" :weight 10}
         (read-config
          (source "test-resources/lib_tests/aero/hosts.edn")
          {:profile :default :hostname "emerald"})))
  (is (= {:color "black" :weight nil}
         (read-config (source "test-resources/lib_tests/aero/hosts.edn")
                      {:profile :default :hostname "granite"})))
  (is (= {:color "white" :weight nil}
         (read-config (source "test-resources/lib_tests/aero/hosts.edn")
                      {:profile :default :hostname "diamond"}))))

(deftest define-new-type-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= :chocolate (:flavor config)))))

(deftest join-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" (str (env "TERM")))
           (:dumb-term config)))
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" "smart")
           (:smart-term config)))))

#?(:clj
   (deftest test-read
     (let [x [:foo :bar :baz]
           _ (System/setProperty "DUMMY_READ" (str x))
           config (read-config "test-resources/lib_tests/aero/config.edn")]
       (is (= x (:test-read-str config)))
       (is (= x (:test-read-env config)))
       (System/clearProperty "DUMMY_READ"))))

(deftest envf-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" (str (env "TERM")))
           (:dumb-term-envf config)))))

#?(:clj
   (deftest prop-test
     (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
       (is (= "dummy" (:triple-or config)))
       (is (nil? (:prop config))))
     (System/setProperty "DUMMY" "ABC123")
     (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
       (is (= "ABC123" (:triple-or config)))
       (is (= "ABC123" (:prop config))))
     (System/clearProperty "DUMMY")))

(deftest numeric-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= 1234 (:long config)))
    (is (= 4567.8 (:double config))))
  #?@(:clj [(System/setProperty "FOO" "123")
           (let [config (read-config "test-resources/lib_tests/aero/long_prop.edn")]
             (is (= 123 (:long-prop config))))
           (System/clearProperty "FOO")]))

(deftest keyword-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= :foo/bar (:keyword config)))
    (is (= :foo/bar (:already-a-keyword config)))
    (is (= :abc (:env-keyword config)))))

(deftest boolean-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= true (:True-boolean config)))
    (is (= true (:true-boolean config)))
    (is (= false (:trivial-false-boolean config)))
    (is (= false (:nil-false-boolean config)))
    (is (= false (:false-boolean config)))))

(deftest format-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "My favorite flavor is %s %s" (or (env "TERM") "flaa") :chocolate)
           (:flavor-string config)))))

(deftest ref-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (get-in config [:greeting])
           (:test config)))))

(deftest complex-ref-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (get-in config [:refer-me :a :b 1234])
           (:complex-ref config)))))

(deftest remote-file-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (get-in config [:remote :greeting])
           "str"))))

(deftest nested-ref-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn" {})]
    (is (= "Hello World!" (get-in config [:test-nested])))))

(deftest profile-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn" {:profile :dev})]
    (is (= 8000 (:port config))))
  (let [config (read-config "test-resources/lib_tests/aero/config.edn" {:profile :prod})]
    (is (= 80 (:port config)))))

(deftest dummy-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn" {:profile :dev})]
    (is (= "dummy" (:dummy config)))))

#?(:clj
   (deftest resolver-tests
     (let [source (io/resource "aero/includes.edn")]
       (is (read-config source {:profile :relative}))
       (is (read-config source {:profile :relative-abs}))
       (is (read-config source {:profile :resource :resolver resource-resolver}))
       (is (read-config source {:profile :file :resolver root-resolver}))
       (is (read-config (-> source slurp string-reader)
                        {:profile :relative-abs}))
       (is (read-config source {:profile  :map
                                :resolver {:sub-includes (io/resource "aero/sub/includes.edn")
                                           :valid-file   (io/resource "aero/valid.edn")}}))
       (is (:aero/missing-include (read-config source {:profile :file-does-not-exist}))))))

(deftest missing-include-test
  (let [source "test-resources/lib_tests/aero/includes.edn"]
    (is (:aero/missing-include (read-config source {:profile :file-does-not-exist})))))

(deftest dangling-ref-test
  (is (= {:user {:favorite-color :blue}
          :gardner {:favorite-color :blue}
          :karl {:favorite-color :blue}
          :color :blue}
         (read-config
           (string-reader
             (binding [*print-meta* true]
               (pr-str {:user ^:ref [:karl]
                        :gardner {:favorite-color ^:ref [:color]}
                        :karl ^:ref [:gardner]
                        :color :blue})))))))

(deftest deferred-test
  ;; TODO:
  #_(is
   (instance? Deferred (deferred (+ 2 2))))
  ;; The basic idea here is to ensure that the #expensive-network-call
  ;; tag literal is called (because it increments its value). This
  ;; also tests the Deferred functionality as a consequence.
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (get-in config [:network-call])
           8))))

(deftest default-reader-combo-test
  (let [config (read-config "test-resources/lib_tests/aero/default-reader.edn")]
    (is (= #inst "2013-07-09T18:05:53.231-00:00" (:date config)))))

(deftest refs-call-once-test
  ;; The purpose of this test is to defend against naïve copying of references
  ;; instead of resolving it early
  (let [before-call-count @network-call-count
        config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= (inc before-call-count) @network-call-count))))

(deftest falsey-user-return-test
  (let [config (read-config "test-resources/lib_tests/aero/config.edn")]
    (is (= nil (config :falsey-user-return)))))

(deftest ref-in-set-test
  (is (= #{10}
         (:bar
           (read-config
             (string-reader
               "{:foo 10 :bar #{#ref [:foo]}}"))))))

(deftest or-incomplete-child
  (let [config-str "{:x \"foo\"
                   :y #or [#ref [:x] \"bar\"]
                   :junk0 \"0\"
                   :junk1 \"1\"
                   :junk2 \"2\"
                   :junk3 \"3\"
                   :junk4 \"4\"
                   :junk5 \"5\"
                   :junk6 \"6\"
                   :junk7 \"7\"
                   :junk8 \"8\"}"
        config (string-reader config-str)]
    (is (= "foo" (:x (read-config config))))))

(deftest or-dangling-ref
  (let [config-str "{:y #or [#ref [:x] \"bar\"]}"
        config (string-reader config-str)]
    (is (= "bar" (:y (read-config config))))))

(deftest meta-preservation-test
  (are [ds] (= ds
               (::foo
                 (meta
                   (read-config
                     (string-reader
                       (binding [*print-meta* true]
                         (pr-str (with-meta ds {::foo ds}))))))))
    []
    {}
    #{}
    ()
    [1]
    {:a :b}
    #{:a :b}
    '(1)))
