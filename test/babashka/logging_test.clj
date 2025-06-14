(ns babashka.logging-test
  (:require  [babashka.fs :as fs]
             [babashka.test-utils :as tu]
             [clojure.edn :as edn]
             [clojure.string :as str]
             [clojure.test :as t :refer [deftest is testing]]))

(def program
  '(do
     (ns dude)
     (require '[clojure.tools.logging :as log])
     (require '[taoensso.timbre :as timbre])

     (defn test-fn
       []
       (log/debug "test ctl debug level")
       (log/info "test ctl info")
       (timbre/debug "test timbre debug level")
       (timbre/info "test timbre info"))

     (println "before setting log level")
     (test-fn)

     (def old-config timbre/*config*)
     (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

     (println "after setting log level to :info")
     (test-fn)

     (println "with-level :debug")
     (timbre/with-level :debug
       (test-fn))

     (timbre/set-level! :no-crash)
     (def x (atom nil))
     (try (timbre/set-min-level! :crash)
          (catch Exception _ (reset! x :crash)))
     (assert (= :crash @x))
     (timbre/set-min-level! :debug)
     (println "after setting log level to :debug")
     (test-fn)

     (timbre/infof "Hello %s" 123)
     (log/infof "Hello %s" 123)

     (timbre/swap-config! assoc-in [:appenders :spit] (timbre/spit-appender {:fname "/tmp/timbre.log"}))
     (log/infof "Hello %s" 123)
     (timbre/swap-config! (constantly old-config))))

(deftest logging-test
  (let [res (tu/bb nil (pr-str program))]
    (is (= 8 (count (re-seq #"\[dude:.\]" res))))
    (is (= 6 (count (re-seq #"DEBUG" res))))
    (is (= 11 (count (re-seq #"INFO" res)))))
  (testing "println appender works with with-out-str"
    (let [res (tu/bb
               nil
               (pr-str '(do
                          (require '[taoensso.timbre :as timbre]
                                   '[clojure.string :as str])
                          (str/includes? (with-out-str (timbre/info "hello")) "hello"))))
          res (edn/read-string res)]
      (is (true? res))))
  (testing "spit-appender"
    (let [temp-file (-> (fs/create-temp-dir)
                        (fs/file "log.txt"))
          program   (pr-str '(do
                               (require '[taoensso.timbre :as timbre]
                                        '[clojure.string :as str])
                               (def appender (timbre/spit-appender {:fname :fname-placeholder}))
                               (def old-config timbre/*config*)
                               (timbre/swap-config! assoc-in [:appenders :spit] appender)
                               (str/includes? (with-out-str (timbre/info "hello")) "hello")
                               (timbre/swap-config! (constantly old-config))))
          program   (str/replace program ":fname-placeholder" (pr-str (.getPath temp-file)))
          _         (tu/bb
                     nil
                     program)
          res       (slurp temp-file)]
      (is (str/includes? res "hello")))))

(deftest timbre-spy-test
  (let [res (tu/bb nil (pr-str '(taoensso.timbre/spy :foo)))]
    (is (str/includes? res ":foo => :foo"))))

(def readable-prog
  '(do
     (ns readble-test)
     (require '[clojure.tools.logging.readable :as logr])
     (require '[taoensso.timbre :as timbre])

     (defn test-fn []
       (logr/trace (ex-info "trace exception" {}))
       (logr/debugf "%s" {"abc" 123 "def" 789})
       (logr/info (list \a \b))
       (logr/warnf "%s" "test warn")
       (let [g (logr/spyf "%s" (apply str (interpose "," ["abc" "def" "ghi"])))]
         (println g)))

     (println "before setting anything")
     (test-fn)

     (println "with print-readably set to nil (overridden by log macros)")
     (binding [*print-readably* nil]
       (test-fn))

     (println "setting log level")
     (timbre/set-level! :warn)
     (test-fn)
     (timbre/set-level! :debug)))

(deftest readable-logging-test
  (let [res (tu/bb nil (pr-str readable-prog))]
    (testing "spied value is returned and printed (and printed from println even though spyf level isn't enabled)"
      (is (= 5 (count (re-seq #"abc,def,ghi" res)))))
    (testing "spied value is printed readably as a result of spyf"
      (is (= 2 (count (re-seq #"\"abc,def,ghi\"" res)))))
    (testing "strings logged are printed readably"
      (is (= 3 (count (re-seq #"\"test warn\"" res)))))
    (testing "lists are printed readably"
      (is (= 2 (count (re-seq #"\(\\a \\b\)" res)))))))

(deftest timbre-log!-test
  (is (str/includes? (tu/bb nil
                            (pr-str '(do (require '[taoensso.timbre :as timbre])
                                         (defn log-wrapper [& args]
                                           (timbre/log! :info :p args))
                                         (log-wrapper "hallo"))))
                     "hallo")))
