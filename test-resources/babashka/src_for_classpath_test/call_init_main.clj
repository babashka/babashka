(ns call-init-main
  (:require [init-test :as i]))

(defn foobar [] (str (i/do-a-thing) "bar"))

(defn -main [& _] (i/do-a-thing))
