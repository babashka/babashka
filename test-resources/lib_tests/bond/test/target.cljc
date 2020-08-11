(ns bond.test.target)

(defn foo
  [x]
  (* 2 x))

(defn- private-foo
  [x]
  (* 2 x))

(defn foo-caller [x]
  (foo x))

(defn bar
  [x]
  (println "bar!") (* 2 x))

(defn quux
  [a b & c]
  c)

(defn quuk
  [a b & c]
  c)

(defmacro baz
  [x]
  `(* ~x 2))

(def without-arglists
  (fn [x]
    (* 2 x)))
