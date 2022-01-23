(ns clojure.term.colors-test
  (:require [clojure.test :refer :all]
            [clojure.term.colors :refer :all]))

(defn get-fn
  "get function from symbol in clojure.term.colors package"
  [fname]
  (ns-resolve (the-ns 'clojure.term.colors)
              (-> fname name symbol)))

(defn test-colors-from-map
  "test print colors from a color map"
  [colormap & more]
  (eval
   `(do ~@(map (fn [[color _]]
                 `(println ((get-fn ~color)
                            (name ~color) (str ~@more))))
               colormap))))

(deftest color-test
  (testing "Testing colors."
    (test-colors-from-map *colors* " foreground.")
    (test-colors-from-map *highlights* " background.")
    (test-colors-from-map *attributes* " attributes."))

  (testing "Testing disable colors."
    (binding [*disable-colors* true]
      (println  \newline "When disabled-colors is set ...")
      (test-colors-from-map *colors* " foreground."))))
