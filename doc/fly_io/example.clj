(ns example
  (:require [hiccup2.core :refer [html]]
            [org.httpkit.server :refer [run-server]]))

(def port (or (some-> (System/getenv "PORT")
                      parse-long)
              8092))

(run-server
 (fn [_]
   {:body
    (str (html
          [:html
           [:body
            [:h1 "Hello world!"]
            [:p (str "This site is running with babashka v"
                     (System/getProperty "babashka.version"))]]]))})
 {:port port})

(println "Site running on port" port)
@(promise)
