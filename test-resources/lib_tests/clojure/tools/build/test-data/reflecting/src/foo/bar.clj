(ns foo.bar
  (:gen-class))

(defn foo [s] (.length s))

(defn -main [& args]
  (println (foo "abc")))
