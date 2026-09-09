(ns foo.bar
  "__REPLACE__"
  (:gen-class))

(defn hello [] (println "hello"))

(defn -main
  [& args]
  (hello))
