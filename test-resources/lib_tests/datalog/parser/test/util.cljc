(ns datalog.parser.test.util
  (:require [#?(:clj clojure.test :cljs cljs.test) :as test]))

#?(:clj
   (defmethod test/assert-expr 'thrown-msg? [msg form]
     (let [[_ match & body] form]
       `(try ~@body
             (test/do-report {:type :fail, :message ~msg, :expected '~form, :actual nil})
             (catch Throwable e#
               (let [m# (.getMessage e#)]
                 (test/do-report
                  {:type     (if (= ~match m#) :pass :fail)
                   :message  ~msg
                   :expected '~form
                   :actual   e#}))
               e#)))))
