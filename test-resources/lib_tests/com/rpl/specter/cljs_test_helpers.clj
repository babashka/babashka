(ns com.rpl.specter.cljs-test-helpers)

;; it seems like gen/bind and gen/return are a monad (hence the names)
(defmacro for-all+ [bindings & body]
  (let [parts (partition 2 bindings)
        vars (vec (map first parts))
        genned (reduce
                (fn [curr [v code]]
                  `(clojure.test.check.generators/bind ~code (fn [~v] ~curr)))
                `(clojure.test.check.generators/return ~vars)
                (reverse parts))]
    `(clojure.test.check.properties/for-all [~vars ~genned]
                   ~@body)))
