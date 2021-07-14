(ns babashka.logging-test
  (:require  [babashka.fs :as fs]
             [babashka.test-utils :as tu]
             [clojure.edn :as edn]
             [clojure.test :as t :refer [deftest is testing]]
             [clojure.string :as str]))

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

     (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

     (println "after setting log level to :info")
     (test-fn)

     (println "with-level :debug")
     (timbre/with-level :debug
       (test-fn))

     (timbre/set-level! :debug)
     (println "after setting log level to :debug")
     (test-fn)

     (timbre/infof "Hello %s" 123)
     (log/infof "Hello %s" 123)

     (timbre/swap-config! assoc-in [:appenders :spit] (timbre/spit-appender {:fname "/tmp/timbre.log"}))
     (log/infof "Hello %s" 123)))

(deftest logging-test
  (let [res (tu/bb nil (pr-str program))]
    (is (= 17 (count (re-seq #"\[dude:.\]" res))))
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
          program (pr-str '(do
                             (require '[taoensso.timbre :as timbre]
                                      '[clojure.string :as str])
                             (def appender (timbre/spit-appender {:fname "{{fname}}"}))
                             (timbre/swap-config! assoc-in [:appenders :spit] appender)
                             (str/includes? (with-out-str (timbre/info "hello")) "hello")))
          program (str/replace program "{{fname}}" (str temp-file))
          _ (tu/bb
               nil
               program)
          res (slurp temp-file)]
      (is (str/includes? res "hello")))))
