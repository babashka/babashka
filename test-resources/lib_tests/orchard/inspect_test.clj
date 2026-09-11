(ns orchard.inspect-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.matchers :as matchers]
   [orchard.inspect :as inspect]
   [orchard.misc :as misc :refer [java-api-version]]
   [orchard.test.util :refer [is+]]))

;; Simplifies writing test structures for `match?`.

(def nil-result
  ["Value: nil" [:newline] [:newline] "--- Contents:" [:newline] string? [:newline]])

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def eval-result (eval (read-string code)))

(def inspect-result
  ["Class: "
   [:value "clojure.lang.PersistentTreeMap" 0]
   [:newline]
   "Count: 4"
   [:newline]
   [:newline]
   "--- Contents:"
   [:newline]
   "  " [:value ":a" 1] " = " [:value "{:b 1}" 2]
   [:newline]
   "  " [:value ":c" 3] " = " [:value "\"a\"" 4]
   [:newline]
   "  " [:value ":d" 5] " = " [:value "e" 6]
   [:newline]
   "  " [:value ":f" 7] " = " [:value "[2 3]" 8]
   [:newline] [:newline]
   #"--- View mode" [:newline] "  ●normal object pretty sort-maps"])

(def long-sequence (range 70))
(def long-vector (vec (range 70)))
(def long-map (into (sorted-map) (zipmap (range 70) (range 70))))
(def long-nested-coll (vec (map #(range (* % 10) (+ (* % 10) 80)) (range 200))))

(defrecord TestRecord [a b c d])

(defn- section-name [item]
  (when (string? item)
    (second (re-matches #"^--- ([\w ]+)(?: .*:|:)$" item))))

(defn- trim-newlines [section]
  (loop [section (vec section)]
    (if (= (peek section) [:newline])
      (recur (pop section))
      (vec (drop-while #{[:newline]} section)))))

(defn- group-sections [rendered]
  (loop [[c & r] (conj (vec rendered) :end), current-section-name nil, current-section [], sections {}]
    (cond (nil? c) sections

          (or (section-name c) (= c :end))
          (recur r (section-name c) []
                 (assoc sections current-section-name (trim-newlines current-section)))

          :else (recur r current-section-name (conj current-section c) sections))))

(defn- section [rendered name]
  (get (group-sections rendered) name))

(defn- contents-section [rendered]
  (section rendered "Contents"))

(defn- header [rendered]
  (get (group-sections rendered) nil))

(defn- page-size-info [rendered]
  (when-let [sec (section rendered "Page Info")]
    (last sec)))

(defn inspect
  [value & [config]]
  (inspect/start config value))

(defn render
  [inspector]
  (reduce (fn [acc x]
            (let [lst (peek acc)]
              (if (and (string? x) (string? lst))
                (conj (pop acc) (str lst x))
                (conj acc x))))
          [] (:rendered inspector)))

(defn set-page-size [inspector new-size]
  (inspect/refresh inspector {:page-size new-size}))

(defn set-pretty-print [inspector pretty-print]
  (inspect/refresh inspector {:pretty-print pretty-print}))

(deftest nil-test
  (testing "nil renders correctly"
    (is+ nil-result (-> (inspect nil) render))))

(deftest pop-empty-test
  (testing "popping an empty inspector renders nil"
    (is+ nil-result
         (-> (inspect nil)
             inspect/up
             render))))

(deftest pop-empty-idempotent-test
  (testing "popping an empty inspector is idempotent"
    (is+ nil-result
         (-> (inspect nil)
             inspect/up
             inspect/up
             render))))

(deftest push-empty-test
  (testing "pushing an empty inspector index renders nil"
    (is+ nil-result
         (-> (inspect nil)
             (inspect/down 1)
             render))))

(deftest push-empty-idempotent-test
  (testing "pushing an empty inspector index is idempotent"
    (is+ nil-result
         (-> (inspect nil)
             (inspect/down 1)
             (inspect/down 1)
             render))))

(def any-var 42)

(deftest inspect-var-test
  (testing "inspecting a var"
    (is+ {nil
          ["Class: "
           [:value "sci.lang.Var" 0]
           [:newline]
           "Value: "
           [:value "42" 1]]

          "Meta Information"
          ["  " [:value ":line" pos?] " = " [:value string? pos?]
           [:newline]
           "  " [:value ":column" pos?] " = " [:value string? pos?]
           [:newline]
           "  " [:value ":ns" pos?] " = " [:value "#namespace[orchard.inspect-test]" pos?]
           [:newline]
           "  " [:value ":name" pos?] " = " [:value "any-var" pos?]
           [:newline]
           "  " [:value ":file" pos?] " = " [:value string? pos?]]}
         (-> (inspect #'any-var) render group-sections))))

(deftest inspect-expr-test
  (testing "rendering an expr"
    (is+ inspect-result
         (-> (inspect eval-result) render))))

(deftest push-test
  (testing "pushing a rendered expr inspector idx"
    (is+ {nil
          ["Class: " [:value "clojure.lang.PersistentArrayMap" number?] [:newline]
           "Count: 1"]

          "Contents"
          ["  " [:value ":b" number?] " = " [:value "1" number?]]

          "Path"
          ["  :a"]}
         (-> (inspect eval-result) (inspect/down 2) render group-sections))))

(deftest pop-test
  (testing "popping a rendered expr inspector"
    (is+ inspect-result
         (-> (inspect eval-result)
             (inspect/down 2)
             inspect/up
             render))))

(deftest pagination-test
  (testing "big collections are paginated"
    (is+ 33 (count (:index (inspect long-sequence))))
    ;; Twice more for maps
    (is+ 65 (count (:index (inspect long-map))))
    (is (-> (inspect long-vector)
            render
            page-size-info)))
  (testing "small collections are not paginated"
    (is+ nil (-> (inspect (range 10))
                 render
                 page-size-info)))
  (testing "changing page size"
    (is+ 21 (count (:index (-> (inspect long-sequence)
                               (set-page-size 20)))))
    (is+ 41 (count (:index (-> (inspect long-map)
                               (set-page-size 20)))))
    (is+ nil (-> (inspect long-sequence)
                 (set-page-size 200)
                 render
                 page-size-info)))
  (testing "uncounted collections have their size determined on the last page"
    (is+ "  Page size: 32, showing page: 2 of 2"
         (-> (inspect (range 50))
             inspect/next-page
             render
             page-size-info)))
  (testing "next-page and prev-page are bound to collection size"
    (is (= 0
           (-> (inspect [])
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               :current-page)))
    (is (= (inspect [])
           (-> (inspect [])
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page)))
    (is (= 2
           (-> (inspect long-vector)
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               :current-page)))
    (is (= 0
           (-> (inspect long-vector)
               inspect/prev-page
               inspect/prev-page
               :current-page)))
    (is (= (inspect [])
           (-> (inspect [])
               inspect/prev-page
               inspect/prev-page
               inspect/prev-page)))
    (is (= 1
           (-> (inspect long-vector)
               inspect/next-page
               inspect/next-page
               inspect/prev-page
               inspect/next-page
               inspect/prev-page
               :current-page))))
  (testing "page numbers are tracked per nesting level"
    (let [ins (-> (inspect long-nested-coll)
                  inspect/next-page
                  inspect/next-page
                  inspect/next-page
                  inspect/next-page)]
      (is (= 4 (:current-page ins)))
      (let [ins (-> ins
                    (inspect/down 1)
                    inspect/next-page
                    inspect/next-page)]
        (is (= 2 (:current-page ins)))
        (is (= 4 (:current-page (inspect/up ins)))))))
  (testing "pagination commands are no-ops on non-pageable objects"
    (let [ins (inspect 42)]
      (is+ (render ins)
           (render (-> ins
                       inspect/next-page
                       inspect/next-page
                       inspect/prev-page
                       inspect/prev-page))))
    ;; Atom deref contents are non-pageable.
    (let [ins (inspect (atom (range 100)))]
      (is (= (render ins)
             (render (-> ins
                         inspect/next-page
                         inspect/next-page
                         inspect/prev-page
                         inspect/prev-page)))))))

(deftest def-value-test
  (testing "define var with the currently inspected value"
    (-> (inspect eval-result)
        (inspect/down 2)
        (inspect/down 2)
        (inspect/def-current-value *ns* "--test-val--"))
    (is (= 1 @(resolve '--test-val--)))))

(deftest down-test
  (testing "basic down"
    (is (= 2 (-> (inspect (list 1 2))
                 (inspect/down 2)
                 :value)))
    (is (= 2 (-> (inspect [1 2])
                 (inspect/down 2)
                 :value)))
    (is (= 1 (-> (inspect {:a 1 :b 2})
                 (inspect/down 2)
                 :value)))
    (is (= 2 (-> (inspect #{1 2})
                 (inspect/down 2)
                 :value)))
    (is (= :a (-> (inspect '{:foo [:a :b :c]})
                  (inspect/down 2)
                  (inspect/down 1)
                  :value)))
    (is (= 19 (-> (inspect long-sequence)
                  (inspect/down 20)
                  :value)))
    (is (= 9 (-> (inspect long-map)
                 (inspect/down 20)
                 :value))))
  (testing "down with pagination"
    (is (= long-sequence (-> (inspect long-sequence)
                             (set-page-size 2)
                             (inspect/down 20)
                             :value)))
    (is (= long-map (-> (inspect long-map)
                        (set-page-size 2)
                        (inspect/down 20)
                        :value)))
    (is (= 19 (-> (inspect long-map)
                  (inspect/down 40)
                  :value))))
  (testing "doesn't go out of boundaries"
    (is (= [1 2] (-> (inspect [1 2])
                     (inspect/down 10)
                     :value)))
    (is (= [1 2] (-> (inspect [1 2])
                     (set-page-size 1)
                     (inspect/down 10)
                     :value)))
    (is (= [1 2] (-> (inspect [1 2])
                     (inspect/down 5)
                     (inspect/down -10)
                     :value)))
    (is (= 2 (-> (inspect [1 2])
                 (inspect/down 1)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 :value)))
    (is (= 2 (-> (inspect {:a 1 :b 2})
                 (inspect/down 4)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 :value)))
    (is (= 1 (-> (inspect {:a 1 :b 2})
                 (set-page-size 1)
                 (inspect/down 2)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 :value)))
    (is (= 1 (-> (inspect [1 2])
                 (inspect/down 1)
                 (inspect/previous-sibling)
                 (inspect/previous-sibling)
                 (inspect/previous-sibling)
                 :value)))))

(deftest sibling*-test
  (is (= :c
         (-> (inspect '{:foo [:a :b :c]})
             (inspect/down 2)
             (inspect/down 1)
             (inspect/next-sibling)
             (inspect/next-sibling)
             :value)))
  (is (= :b
         (-> (inspect '{:foo [:a :b :c]})
             (inspect/down 2)
             (inspect/down 1)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/previous-sibling)
             :value)))
  (is (= :a
         (-> (inspect '{:foo [:a :b :c]})
             (inspect/down 2)
             (inspect/down 1)
             (inspect/previous-sibling)
             (inspect/previous-sibling)
             (inspect/previous-sibling)
             (inspect/previous-sibling)
             :value)))
  (is (= :c
         (-> (inspect '{:foo [:a :b :c]})
             (inspect/down 2)
             (inspect/down 1)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             :value)))
  (let [inspector-at-first-sibling (-> (inspect '{:foo [:a :b :c]})
                                       (inspect/down 2)
                                       (inspect/down 1))]
    (is (= inspector-at-first-sibling
           (-> inspector-at-first-sibling
               inspect/previous-sibling
               inspect/previous-sibling
               inspect/previous-sibling
               inspect/previous-sibling))))
  (let [inspector-at-last-sibling (-> (inspect '{:foo [:a :b :c]})
                                      (inspect/down 2)
                                      (inspect/down 1)
                                      (inspect/next-sibling)
                                      (inspect/next-sibling))]
    (is (= inspector-at-last-sibling
           (-> inspector-at-last-sibling
               inspect/next-sibling
               inspect/next-sibling
               inspect/next-sibling
               inspect/next-sibling))))
  (testing "next and previous siblings with pagination"
    (is (= {:value 32 :pages-stack [1]}
           (-> (inspect long-vector)
               (inspect/down 32)
               (inspect/next-sibling)
               (select-keys [:value :pages-stack]))))
    (is (= {:value 31 :pages-stack [0]}
           (-> (inspect long-vector)
               (inspect/next-page)
               (inspect/down 1)
               (inspect/previous-sibling)
               (select-keys [:value :pages-stack]))))
    (is (= 3
           (-> (inspect long-vector)
               (set-page-size 1)
               (inspect/down 1)
               (inspect/next-sibling)
               (inspect/next-sibling)
               (inspect/next-sibling)
               (inspect/up)
               :current-page)))
    (is (= 28 (-> (inspect long-vector)
                  (inspect/down 32)
                  (inspect/previous-sibling)
                  (inspect/previous-sibling)
                  (inspect/previous-sibling)
                  :value)))
    (is (= 32 (-> (inspect long-vector)
                  (inspect/down 32)
                  (inspect/next-sibling)
                  :value))))
  (testing "next-sibling doesn't fall beyond the last element."
    (is (= 3 (-> (inspect [1 2 3])
                 (inspect/down 2)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 (inspect/next-sibling)
                 :value)))
    (is (= 69 (-> (inspect long-sequence)
                  (inspect/next-page)
                  (inspect/next-page)
                  (inspect/down 4)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  (inspect/next-sibling)
                  :value))))
  (testing "sibling functions work with arrays"
    (is+ {:value 35, :pages-stack [1], :path '[(nth 35)]}
         (-> (inspect (long-array (range 40)))
             (inspect/down 33)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)
             (inspect/next-sibling)))))

(deftest replace-test
  (let [ins (inspect [5 4 3 2 1])]
    (is+ ["  0. " [:value "5" 1] [:newline] "  1. " [:value "4" 2] [:newline] "  2. " [:value "3" 3] [:newline] "  3. " [:value "2" 4] [:newline] "  4. " [:value "1" 5]]
         (-> ins render contents-section))

    (let [ins (inspect/replace ins [1 2 3 4 5])]
      (is+ ["  0. " [:value "1" 1] [:newline] "  1. " [:value "2" 2] [:newline] "  2. " [:value "3" 3] [:newline] "  3. " [:value "4" 4] [:newline] "  4. " [:value "5" 5]]
           (-> ins render contents-section))

      (testing "upping recovers old value"
        (is+ ["  0. " [:value "5" 1] [:newline] "  1. " [:value "4" 2] [:newline] "  2. " [:value "3" 3] [:newline] "  3. " [:value "2" 4] [:newline] "  4. " [:value "1" 5]]
             (-> ins inspect/up render contents-section))))))

(deftest path-test
  (is+ ["  :a (nth 2) :b :c (nth 73)"]
       (-> (inspect {:a (list 1 2 {:b {:c (vec (map (fn [x] {:foo (* x 10)}) (range 100)))}})
                     :z 42})
           (inspect/down 1)
           (inspect/up)
           (inspect/down 2)
           (inspect/down 2)
           (inspect/up)
           (inspect/down 3)
           (inspect/down 2)
           (inspect/down 2)
           inspect/next-page
           inspect/next-page
           (inspect/down 10)
           render
           (section "Path")))
  (testing "inspector tracks the path in the data structure beyond the first page with custom page size"
    (is+ ["  (get 2)"]
         (-> long-map inspect
             (set-page-size 2)
             (inspect/next-page)
             (inspect/down 2)
             render
             (section "Path"))))
  (testing "doesn't show path if unknown navigation has happened"
    (is+ nil (-> (inspect long-map) (inspect/down 39) render (section "Path")))
    (is+ nil (-> (inspect long-map) (inspect/down 40) (inspect/down 0) render (section "Path")))
    (is+ nil (-> (inspect long-map) (inspect/down 40) (inspect/down 0) (inspect/down 1) render (section "Path"))))
  (testing "doesn't show the path in the top level"
    (is+ nil (-> (inspect [1 2 3]) render (section "Path")))))

(deftest inspect-class-fields-test
  (testing "inspecting a class with fields renders correctly"
    (is+ ["  " [:value "public static final Boolean FALSE" pos?]
          [:newline]
          "  " [:value "public static final Boolean TRUE" pos?]
          [:newline]
          "  " [:value "public static final Class<Boolean> TYPE" pos?]]
         (-> (inspect Boolean) render (section "Fields"))))
  (testing "inspecting a class without fields renders correctly"
    (is+ nil (-> (inspect Object) render (section "Fields")))))

(deftest inspect-coll-test
  (testing "inspect :coll prints contents of the coll"
    (is+ (matchers/prefix
          ["Class: "
           [:value "clojure.lang.PersistentVector" number?]
           [:newline]
           "Count: 4"
           [:newline]
           [:newline]
           "--- Contents:"
           [:newline]
           "  0. " [:value "1" number?]
           [:newline]
           "  1. " [:value "2" number?]
           [:newline]
           "  2. " [:value "nil" number?]
           [:newline]
           "  3. " [:value "3" number?]
           [:newline]])
         (render (inspect [1 2 nil 3]))))

  (testing "inspect :coll aligns index numbers so that values appear aligned"
    (is+ (matchers/prefix
          ["Class: "
           [:value "clojure.lang.PersistentVector" number?]
           [:newline]
           "Count: 11"
           [:newline]
           [:newline]
           "--- Contents:"
           [:newline]
           "   0. " [:value "0" number?]
           [:newline]
           "   1. " [:value "1" number?]
           [:newline]
           "   2. " [:value "2" number?]
           [:newline]
           "   3. " [:value "3" number?]
           [:newline]
           "   4. " [:value "4" number?]
           [:newline]
           "   5. " [:value "5" number?]
           [:newline]
           "   6. " [:value "6" number?]
           [:newline]
           "   7. " [:value "7" number?]
           [:newline]
           "   8. " [:value "8" number?]
           [:newline]
           "   9. " [:value "9" number?]
           ;; Numbers above have padding, "10" below doesn't.
           [:newline]
           "  10. " [:value "10" number?]
           [:newline]])
         (render (inspect (vec (range 11))))))

  (testing "inspect :coll aligns index numbers correctly for page size > 100"
    (let [rendered (-> (inspect (vec (range 101)))
                       (set-page-size 200)
                       render)
          tail (take-last 2 (contents-section rendered))]
      (is+ (matchers/prefix
            ["Class: "
             [:value "clojure.lang.PersistentVector" number?]
             [:newline]
             "Count: 101"
             [:newline]
             [:newline]
             "--- Contents:"
             [:newline]
             "    0. " [:value "0" number?]])
           rendered)
      ;; "  0" has two spaces of padding, "100" below has none.
      (is+ ["  100. " [:value "100" pos?]] tail))))

(deftest inspect-coll-meta-test
  (testing "inspecting a collection with metadata renders the metadata section"
    (testing "renders the meta information section"
      (is+ ["  " [:value ":m" 1] " = " [:value "42" 2]]
           (-> (inspect (with-meta [:a :b :c :d :e] {:m 42}))
               render
               (section "Meta Information"))))

    (testing "meta values can be navigated to"
      (is (= 42 (-> (inspect (with-meta [:a :b :c :d :e] {:m 42}))
                    (inspect/down 2)
                    :value))))

    (testing "regular values can be navigated to"
      (is (= :a (-> (inspect (with-meta [:a :b :c :d :e] {:m 42}))
                    (inspect/down 3)
                    :value)))
      (is (= :e (-> (inspect (with-meta [:a :b :c :d :e] {:m 42}))
                    (inspect/down 7)
                    :value))))

    (testing "if meta is larger than page size, render it as a single value"
      (is+ ["  " [:value "{0 0, 7 7, 1 1, 4 4, 15 15, ...}" pos?]]
           (-> [:a :b :c :d :e]
               (with-meta (zipmap (range 20) (range)))
               inspect
               (set-page-size 10)
               render
               (section "Meta Information"))))))

(deftest inspect-configure-length-test
  (testing "inspect respects :max-atom-length and :max-coll-size configuration"
    (is+ (matchers/prefix
          ["Class: "
           [:value "clojure.lang.Persist..." 0]
           [:newline]
           "Count: 1"
           [:newline]
           [:newline]
           "--- Contents:"
           [:newline]
           "  0. " [:value "[111111 2222 333 ...]" 1]
           [:newline]])
         (-> [[111111 2222 333 44 5]]
             (inspect {:max-atom-length 20, :max-coll-size 3})
             render)))
  (testing "inspect respects :max-value-length configuration"
    (is+ (matchers/prefix
          ["Class: "
           [:value "clojure.lang.PersistentVector" 0]
           [:newline]
           "Count: 1"
           [:newline]
           [:newline]
           "--- Contents:"
           [:newline]
           "  0. " [:value "(\"long value\" \"long value\" \"long value\" \"long valu..." 1]
           [:newline]])
         (-> [(repeat "long value")]
             (inspect {:max-value-length 50})
             render)))

  (testing "inspect respects :max-value-depth configuration"
    (is+ (matchers/prefix
          ["Class: "
           [:value "clojure.lang.PersistentVector" 0]
           [:newline]
           "Count: 1"
           [:newline]
           [:newline]
           "--- Contents:"
           [:newline]
           "  0. " [:value "[[[[[[...]]]]]]" 1]
           [:newline]])
         (-> [[[[[[[[[[1]]]]]]]]]]
             (inspect {:max-nested-depth 5})
             render))))

(deftest inspect-java-hashmap-test
  (testing "inspecting java.util.Map descendants prints a key-value coll"
    (let [^java.util.Map the-map {:a 1, :b 2, :c 3}
          rendered (render (inspect (java.util.HashMap. the-map)))
          contents (contents-section rendered)]
      (is+ (matchers/prefix ["Class: "
                             [:value "java.util.HashMap" 0]
                             [:newline]
                             "Count: 3"
                             [:newline]
                             [:newline]])
           rendered)
      (is+ (matchers/embeds ["  " [:value ":a" pos?] " = " [:value "1" pos?] [:newline]])
           contents)
      (is+ (matchers/embeds ["  " [:value ":b" pos?] " = " [:value "2" pos?] [:newline]])
           contents)
      (is+ (matchers/embeds ["  " [:value ":c" pos?] " = " [:value "3" pos?] [:newline]])
           contents))))

(deftest inspect-java-object-test
  (testing "inspecting any Java object prints its fields"
    (is+ (matchers/prefix
          ["Class: " [:value "clojure.lang.TaggedLiteral" 0] [:newline]
           "Value: " [:value "#foo ()" 1] [:newline]
           #"Identity hash code: " [:newline]
           [:newline]
           "--- Instance fields:" [:newline]
           "  " [:value "form" 2] " = " [:value "()" 3] [:newline]
           "  " [:value "tag" 4] " = " [:value "foo" 5] [:newline]
           [:newline]
           "--- Static fields:" [:newline]
           "  " [:value "FORM_KW" 6] " = " [:value ":form" 7] [:newline]
           "  " [:value "TAG_KW" 8] " = " [:value ":tag" 9] [:newline]
           [:newline]
           #"View mode" [:newline]
           "  ●object pretty sort-maps"])
         (render (inspect (clojure.lang.TaggedLiteral/create 'foo ()))))))

(deftest inspect-path
  (testing "basic paths"
    (is (= []
           (-> (inspect [1 2])
               :path)))
    (is (= '[(nth 1)]
           (-> (inspect [1 2])
               (set-page-size 1)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/down 1)
               (inspect/next-sibling)
               :path)))
    (is (= '[:b]
           (-> (inspect {:a 1 :b 2})
               (set-page-size 1)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/down 2)
               :path)))
    (is (= '[<unknown>]
           (-> (inspect {:a 1 :b 2})
               (set-page-size 1)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/next-page)
               (inspect/down 1)
               :path))))
  (testing "inspector keeps track of the path in the inspected structure"
    (let [t {:a (list 1 2 {:b {:c (vec (map (fn [x] {:foo (* x 10)}) (range 100)))}})
             :z 42}
          inspector (-> (inspect t)
                        (inspect/down 1)
                        (inspect/up)
                        (inspect/down 2)
                        (inspect/down 2)
                        (inspect/up)
                        (inspect/down 3)
                        (inspect/down 2)
                        (inspect/down 2)
                        inspect/next-page
                        inspect/next-page
                        (inspect/down 10))]
      (is (= '[:a (nth 2) :b :c (nth 73)] (:path inspector)))
      (is (= '[:a (nth 2) :b :c (nth 73) <unknown>]
             (:path (-> inspector (inspect/down 0)))))
      (is (= '[:a (nth 2) :b :c]
             (:path (-> inspector (inspect/down 0) (inspect/down 0)
                        (inspect/up) (inspect/up) (inspect/up)))))))
  (testing "path tracking works if object has metadata"
    (is (= [:data]
           (-> (inspect (with-meta {:data 1} {:m 2}))
               (inspect/down 4)
               :path)))))

(defn- rendered-hier [indents-and-classnames]
  (mapcat (fn [[indent class]]
            [indent [:value class number?] [:newline]])
          (partition 2 indents-and-classnames)))

(deftest inspect-class-test
  (testing "inspecting the java.lang.Object class"
    (is+ {nil
          ["Name: "
           [:value "java.lang.Object" 0] [:newline]
           "Class: " [:value "java.lang.Class" 1] [:newline]
           "Flags: public"]

          "Constructors"
          ["  " [:value "public Object()" 2]]

          "Methods"
          (matchers/embeds
           [[:value "public final native Class<?> getClass()" pos?]
            [:value "public boolean equals(Object)" pos?]
            [:value "public native int hashCode()" pos?]
            [:value "public final native void notify()" pos?]
            [:value "public final native void notifyAll()" pos?]
            [:value "public String toString()" pos?]
            [:value "public final void wait() throws InterruptedException" pos?]
            [:value "public final void wait(long,int) throws InterruptedException" pos?]])}
         (-> (inspect Object) render group-sections)))

  (testing "inspecting the java.lang.Class class"
    (testing "renders the class hierarchy section"
      (is+ (matchers/prefix ["  " [:value "java.lang.Object" pos?]
                             [:newline]
                             "  " [:value "java.io.Serializable" pos?]
                             [:newline]])
           (-> (inspect Class) render (section "Class hierarchy")))))

  (testing "inspecting the java.io.FileReader class"
    (testing "renders the class hierarchy section"
      (is+ (butlast
            (rendered-hier ["  " "java.io.InputStreamReader"
                            "    " "java.io.Reader"
                            "      " "java.lang.Object"
                            "      " "java.io.Closeable"
                            "        " "java.lang.AutoCloseable"
                            "      " "java.lang.Readable"]))
           (-> (inspect java.io.FileReader) render (section "Class hierarchy")))))
)

(deftest inspect-method-test
  (testing "inspecting the HashMap.computeIfAbsent method"
    (is+ {nil
          ["Class: " [:value "java.lang.reflect.Method" 0] [:newline]
           "Name: computeIfAbsent" [:newline]
           "Flags: public" [:newline]
           "Declaring class: " [:value "java.util.HashMap" 1] [:newline]
           "Return type: " [:value "java.lang.Object" 2]]

          "Parameter types"
          ["  0. " [:value "java.lang.Object" pos?] [:newline]
           "  1. " [:value "java.util.function.Function" pos?]]}
         (-> (some #(when (= (.getName ^java.lang.reflect.Method %) "computeIfAbsent") %)
                   (.getDeclaredMethods java.util.HashMap))
             inspect render group-sections)))

  (testing "inspecting the Future.get method"
    (is+ {nil
          ["Class: " [:value "java.lang.reflect.Method" 0] [:newline]
           "Name: get" [:newline]
           "Flags: public abstract" [:newline]
           "Declaring class: " [:value "java.util.concurrent.Future" 1] [:newline]
           "Return type: " [:value "java.lang.Object" 2]]

          "Checked exceptions"
          ["  0. " [:value "java.lang.InterruptedException" pos?] [:newline]
           "  1. " [:value "java.util.concurrent.ExecutionException" pos?]]}
         (-> (.getDeclaredMethod java.util.concurrent.Future "get" (into-array Class []))
             inspect render group-sections))))

(deftest inspect-atom-test
  (testing "inspecting an atom"
    (is+ {nil
          ["Class: " [:value "clojure.lang.Atom" 0] [:newline]
           #"^Identity hash code:" [:newline]
           "Deref: " [:value "{:a 1}" pos?]]

          "Deref"
          ["  Class: " [:value "clojure.lang.PersistentArrayMap" pos?] [:newline]
           "  Count: 1" [:newline]
           [:newline]
           "  --- Contents:" [:newline]
           "    " [:value ":a" pos?] " = " [:value "1" pos?]]}
         (-> (inspect (atom {:a 1})) render group-sections)))

  (testing "small collection is rendered fully"
    (is+ ["  Class: " [:value "clojure.lang.LongRange" pos?] [:newline]
          "  Count: 3" [:newline]
          [:newline]
          "  --- Contents:" [:newline]
          "    0. " [:value "0" pos?] [:newline]
          "    1. " [:value "1" pos?] [:newline]
          "    2. " [:value "2" pos?]]
         (-> (atom (range 3)) inspect render (section "Deref"))))

  (testing "larger collection is paged"
    (is+ ["  Class: " [:value "clojure.lang.LongRange" pos?] [:newline]
          "  Count: 100" [:newline]
          [:newline]
          "  --- Contents:" [:newline]
          "    0. " [:value "0" pos?] [:newline]
          "    1. " [:value "1" pos?] [:newline]
          "    2. " [:value "2" pos?] [:newline]
          "    ..." [:newline]
          [:newline]
          "  --- Page Info:" [:newline]
          "    Page size: 3, showing page: 1 of 34"]
         (-> (atom (range 100)) (inspect {:page-size 3}) render (section "Deref"))))

  (testing "meta is shown on atoms"
    (is+ ["  " [:value ":foo" pos?] " = " [:value "\"bar\"" pos?]]
         (-> (atom [1 2 3] :meta {:foo "bar"}) inspect render (section "Meta Information")))))

(deftest inspect-atom-infinite-seq-test
  (testing "inspecting an atom holding an infinite seq"
    (is+ {nil
          ["Class: " [:value "clojure.lang.Atom" 0] [:newline]
           #"^Identity hash code:" [:newline]
           "Deref: " [:value "(1 1 1 1 1 ...)" pos?]]

          "Deref"
          ["  Class: " [:value "clojure.lang.Repeat" pos?] [:newline]
           [:newline]
           "  --- Contents:" [:newline]
           "    0. " [:value "1" pos?] [:newline]
           "    1. " [:value "1" pos?] [:newline]
           "    2. " [:value "1" pos?] [:newline]
           "    ..." [:newline] [:newline]
           "  --- Page Info:" [:newline]
           "    Page size: 3, showing page: 1 of ?"]}
         (-> (inspect (atom (repeat 1)))
             (set-page-size 3)
             render
             group-sections))))

(deftest inspect-clojure-string-namespace-test
  (testing "inspecting the clojure.string namespace"
    (is+ {nil
          (matchers/prefix ["Class: " [:value "sci.lang.Namespace" number?]])

          "Refer from"
          ["  " [:value "#namespace[clojure.core]" pos?]
           " = "
           [:value #"^\[#'clojure.core/" pos?]]

          "Imports"
          ["  " [:value #"Class java.lang.Class" pos?]]

          "Interns"
          ["  " [:value #"join #'clojure.string/join" pos?]]}
         (-> (find-ns 'clojure.string) inspect render group-sections))))

(deftest inspect-throwable-test
  (testing "inspecting a throwable"
    (is+ {nil
          ["Class: "
           [:value "clojure.lang.ExceptionInfo" 0] [:newline]
           "Message: BOOM"]

          "Causes"
          ["  BOOM" [:newline]
           "  " [:value "clojure.lang.ExceptionInfo" 1]]}
         (-> (doto ^Throwable (ex-info "BOOM" {})
               (.setStackTrace (into-array StackTraceElement [])))
             inspect render group-sections)))

  (testing "exception with multiple causes"
    (is+ {"Causes"
          ["  Outer" [:newline]
           "  " [:value "clojure.lang.ExceptionInfo" number?] " at "
           [:value string? number?] [:newline]
           [:newline]
           "  Inner" [:newline] "  " [:value "java.lang.RuntimeException" number?] " at "
           [:value string? number?]]

          "Trace"
          (matchers/prefix
           [#" 0\. $" [:value string? number?] [:newline]
            #" 1\. $" [:value string? number?] [:newline]
            #" 2\. $" [:value string? number?] [:newline]
            #" 3\. $" [:value string? number?] [:newline]
            #" 4\. $" [:value string? number?] [:newline]])}
         (-> (ex-info "Outer" {} (RuntimeException. "Inner"))
             inspect render group-sections))))

(deftest inspect-eduction-test
  (testing "inspecting eduction shows its object fields"
    (is+ {nil
          ["Class: "
           [:value "clojure.core.Eduction" 0]
           [:newline]
           "Value: "
           [:value "(0 1 2 3 4 ...)" 1]
           [:newline]
           #"^Identity hash code: "]

          "Page Info"
          matchers/absent}
         (-> (eduction (range 100)) inspect render group-sections))))

(deftest render-counted-length-test
  (testing "inspecting counted collections shows their size upfront"
    (let [rendered (-> (range 10) inspect render)]
      (is+ ["Class: " [:value "clojure.lang.LongRange" 0] [:newline]
            "Count: 10"]
           (header rendered)))
    (let [rendered (-> (zipmap (range 20) (range 20)) inspect render)]
      (is+ ["Class: " [:value "clojure.lang.PersistentHashMap" 0] [:newline]
            "Count: 20"]
           (header rendered)))
    (let [rendered (-> (byte-array 30) inspect render)]
      (is+ ["Class: " [:value #"\[B|byte/1" 0] [:newline]
            "Count: 30" [:newline]
            "Component Type: " [:value "byte" 1]]
           (header rendered)))
    (let [rendered (-> (java.util.HashMap.) inspect render)]
      (is+ ["Class: " [:value "java.util.HashMap" 0] [:newline]
            "Count: 0"]
           (header rendered)))
    (let [rendered (-> (cons 1 (cons 2 nil)) inspect render)]
      (is+ ["Class: " [:value "clojure.lang.Cons" 0] [:newline]
            "Count: 2"]
           (header rendered)))))

(deftest object-view-mode-test
  (testing "in :object view-mode recognized objects are rendered as :default"
    (is+ {"Instance fields"
          (matchers/prefix
           ["  " [:value "_count" pos?] " = " [:value "3" pos?] [:newline]
            "  " [:value "_first" pos?] " = " [:value "1" pos?] [:newline]
            "  " [:value "_hash" pos?] " = " [:value "0" pos?] [:newline]])

          "View mode"
          ["  normal ●object pretty sort-maps"]}
         (-> (inspect (list 1 2 3))
             (inspect/set-view-mode :object)
             render
             group-sections))

    (is+ {"Instance fields"
          ["  " [:value "_meta" pos?] " = " [:value "nil" pos?] [:newline]
           "  " [:value "state" pos?] " = " [:value #"#object\[java.util.concurrent.atomic.AtomicReference" pos?] [:newline]
           "  " [:value "validator" pos?] " = " [:value "nil" pos?] [:newline]
           "  " [:value "watches" pos?] " = " [:value "{}" pos?]]

          "View mode"
          ["  normal ●object pretty sort-maps"]}
         (-> (inspect (atom "foo"))
             (inspect/set-view-mode :object)
             render
             group-sections)))

  (testing "navigating away from an object changes the view mode back to normal"
    (is+ ["  0. " [:value "2" pos?] [:newline]
          "  1. " [:value "3" pos?]]
         (-> (inspect (list 1 2 3))
             (inspect/set-view-mode :object)
             (inspect/down 13)
             render
             contents-section))))

(deftest table-view-mode-test
  (testing "in :table view-mode lists of maps are rendered as tables"
    (is+ {"Contents"
          ["  | " [:value "#" pos?] " | " [:value ":a" pos?] " |   "
           [:value ":bb" pos?] " |      " [:value ":ccc" pos?] " | " [:newline]
           "  |---+----+-------+-----------|" [:newline]
           "  | " [:value "0" pos?] " |  " [:value "0" pos?] " | "
           [:value "\"000\"" pos?] " |        " [:value "()" pos?] " | " [:newline]
           "  | " [:value "1" pos?] " | " [:value "-1" pos?] " | "
           [:value "\"111\"" pos?] " |       " [:value "(1)" pos?] " | " [:newline]
           "  | " [:value "2" pos?] " | " [:value "-2" pos?] " | "
           [:value "\"222\"" pos?] " |     " [:value "(2 1)" pos?] " | " [:newline]
           "  | " [:value "3" pos?] " | " [:value "-3" pos?] " | "
           [:value "\"333\"" pos?] " |   " [:value "(3 2 1)" pos?] " | " [:newline]
           "  | " [:value "4" pos?] " | " [:value "-4" pos?] " | "
           [:value "\"444\"" pos?] " | " [:value "(4 3 2 1)" pos?] " | "]

          "View mode"
          ["  normal ●table object pretty sort-maps"]}
         (-> (for [i (range 5)]
               {:a (- i)
                :bb (str i i i)
                :ccc (range i 0 -1)})
             inspect
             (inspect/set-view-mode :table)
             render
             group-sections)))

  (testing "wide columns are truncated"
    (is+ ["  | " [:value "#" pos?] " |       " [:value ":a" pos?] " |      " [:value ":bb" pos?] " | " [:newline]
          "  |---+----------+----------|" [:newline]
          "  | " [:value "0" pos?] " | " [:value "\"aaaa..." pos?] " | " [:value "\"bbbb..." pos?] " | " [:newline]
          "  | " [:value "1" pos?] " | " [:value "\"aaaa..." pos?] " | " [:value "\"bbbb..." pos?] " | " [:newline]
          "  | " [:value "2" pos?] " | " [:value "\"aaaa..." pos?] " | " [:value "\"bbbb..." pos?] " | "]
         (-> (repeat 3 {:a "aaaaaaaaaa", :bb "bbbbbbbbb"})
             (inspect {:table-column-width 5})
             (inspect/set-view-mode :table)
             render
             (contents-section))))

  (testing "in :table view-mode lists of vectors are rendered as tables"
    (is+ {"Contents"
          ["  | " [:value "#" pos?] " |  " [:value "0" pos?] " |     "
           [:value "1" pos?] " |         " [:value "2" pos?] " | " [:newline]
           "  |---+----+-------+-----------|" [:newline]
           "  | " [:value "0" pos?] " |  " [:value "0" pos?] " | "
           [:value "\"000\"" pos?] " |        " [:value "()" pos?] " | " [:newline]
           "  | " [:value "1" pos?] " | " [:value "-1" pos?] " | "
           [:value "\"111\"" pos?] " |       " [:value "(1)" pos?] " | " [:newline]
           "  | " [:value "2" pos?] " | " [:value "-2" pos?] " | "
           [:value "\"222\"" pos?] " |     " [:value "(2 1)" pos?] " | " [:newline]
           "  | " [:value "3" pos?] " | " [:value "-3" pos?] " | "
           [:value "\"333\"" pos?] " |   " [:value "(3 2 1)" pos?] " | " [:newline]
           "  | " [:value "4" pos?] " | " [:value "-4" pos?] " | "
           [:value "\"444\"" pos?] " | " [:value "(4 3 2 1)" pos?] " | "]

          "View mode"
          ["  normal ●table object pretty sort-maps"]}
         (-> (for [i (range 5)]
               [(- i) (str i i i) (range i 0 -1)])
             inspect
             (inspect/set-view-mode :table)
             render
             group-sections)))

  (testing "breaks if table mode is requested for unsupported value"
    (is (thrown? Exception (-> {:a 1}
                               inspect
                               (inspect/set-view-mode :table)
                               render
                               contents-section))))

  (testing "works with paging"
    (is+ ["  | " [:value "#" pos?] " | " [:value "0" pos?] " | " [:value "1" pos?] " | " [:newline]
          "  |---+---+---|" [:newline]
          "  | " [:value "0" pos?] " | " [:value "0" pos?] " | " [:value "0" pos?] " | " [:newline]
          "  | " [:value "1" pos?] " | " [:value "1" pos?] " | " [:value "1" pos?] " | " [:newline]
          "  | " [:value "2" pos?] " | " [:value "2" pos?] " | " [:value "2" pos?] " | " [:newline]
          "  ..."]
         (-> (map #(vector % %) (range 9))
             inspect
             (set-page-size 3)
             (inspect/set-view-mode :table)
             render
             contents-section))

    (is+ ["  ..." [:newline] [:newline]
          "  | " [:value "#" pos?] " | " [:value "0" pos?] " | " [:value "1" pos?] " | " [:newline]
          "  |---+---+---|" [:newline]
          "  | " [:value "3" pos?] " | " [:value "3" pos?] " | " [:value "3" pos?] " | " [:newline]
          "  | " [:value "4" pos?] " | " [:value "4" pos?] " | " [:value "4" pos?] " | " [:newline]
          "  | " [:value "5" pos?] " | " [:value "5" pos?] " | " [:value "5" pos?] " | " [:newline]
          "  ..."]
         (-> (map #(vector % %) (range 9))
             inspect
             (set-page-size 3)
             (inspect/next-page)
             (inspect/set-view-mode :table)
             render
             contents-section))

    (is+ ["  ..." [:newline] [:newline]
          "  | " [:value "#" pos?] " | " [:value "0" pos?] " | " [:value "1" pos?] " | " [:newline]
          "  |---+---+---|" [:newline]
          "  | " [:value "6" pos?] " | " [:value "6" pos?] " | " [:value "6" pos?] " | " [:newline]
          "  | " [:value "7" pos?] " | " [:value "7" pos?] " | " [:value "7" pos?] " | " [:newline]
          "  | " [:value "8" pos?] " | " [:value "8" pos?] " | " [:value "8" pos?] " | "]
         (-> (map #(vector % %) (range 9))
             inspect
             (set-page-size 3)
             (inspect/next-page)
             (inspect/next-page)
             (inspect/set-view-mode :table)
             render
             contents-section)))

  (testing "map is not reported as table-viewable when paged"
    (is (not (-> (zipmap (range 100) (range))
                 inspect
                 (set-page-size 30)
                 (inspect/view-mode-supported? :table))))))

(deftest hex-view-mode-test
  (testing "in :hex view-mode byte arrays are rendered as hexdump tables"
    (is+ {"Contents"
          ["  0x00000000 │ 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f │ ················" [:newline]
           "  0x00000010 │ 10 11 12 13 14 15 16 17  18 19 1a 1b 1c 1d 1e 1f │ ················" [:newline]
           "  0x00000020 │ 20 21 22 23 24 25 26 27  28 29 2a 2b 2c 2d 2e 2f │  !\"#$%&'()*+,-./" [:newline]
           "  0x00000030 │ 30 31 32 33 34 35 36 37  38 39 3a 3b 3c 3d 3e 3f │ 0123456789:;<=>?" [:newline]
           "  0x00000040 │ 40 41 42 43 44 45 46 47  48 49 4a 4b 4c 4d 4e 4f │ @ABCDEFGHIJKLMNO" [:newline]
           "  0x00000050 │ 50 51 52 53 54 55 56 57  58 59 5a 5b 5c 5d 5e 5f │ PQRSTUVWXYZ[\\]^_" [:newline]
           "  0x00000060 │ 60 61 62 63                                      │ `abc"]

          "View mode"
          ["  ●hex normal object pretty sort-maps"]}
         (-> (byte-array (range 100))
             inspect
             (inspect/set-view-mode :hex)
             render
             group-sections)))

  (testing "works with paging"
    (is+ ["  0x00000000 │ 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f │ ················" [:newline]
          "  0x00000010 │ 10 11 12 13 14 15 16 17  18 19 1a 1b 1c 1d 1e 1f │ ················" [:newline]
          "  ..."]
         (-> (byte-array (range 100))
             inspect
             (inspect/set-view-mode :hex)
             (set-page-size 2)
             render
             contents-section))

    (is+ ["  ..." [:newline]
          "  0x00000020 │ 20 21 22 23 24 25 26 27  28 29 2a 2b 2c 2d 2e 2f │  !\"#$%&'()*+,-./" [:newline]
          "  0x00000030 │ 30 31 32 33 34 35 36 37  38 39 3a 3b 3c 3d 3e 3f │ 0123456789:;<=>?" [:newline]
          "  ..."]
         (-> (byte-array (range 100))
             inspect
             (inspect/set-view-mode :hex)
             (set-page-size 2)
             inspect/next-page
             render
             contents-section))

    (testing "enabled by default for byte arrays"
      (is+ (matchers/prefix
            ["  0x00000000 │ 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f │ ················"])
           (-> (byte-array (range 100))
               inspect
               render
               contents-section))

      (is+ (matchers/prefix
            ["  0x00000000 │ 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f │ ················"])
           (-> [(byte-array (range 100))]
               inspect
               (inspect/down 1)
               render
               contents-section)))))

(deftest toggle-view-mode-test
  (is+ :normal (-> (repeat 10 [1 2]) inspect :view-mode))
  (is+ ["  ●normal table object pretty sort-maps"]
       (-> (repeat 10 [1 2]) inspect render (section "View mode")))

  (is+ :table (-> (repeat 10 [1 2]) inspect inspect/toggle-view-mode :view-mode))
  (is+ ["  normal ●table object pretty sort-maps"]
       (-> (repeat 10 [1 2]) inspect inspect/toggle-view-mode render (section "View mode")))

  (is+ :object (-> (repeat 10 [1 2]) inspect inspect/toggle-view-mode inspect/toggle-view-mode :view-mode))
  (is+ ["  normal table ●object pretty sort-maps"]
       (-> (repeat 10 [1 2]) inspect inspect/toggle-view-mode inspect/toggle-view-mode render (section "View mode")))

  (is+ :normal (-> (repeat 10 [1 2]) inspect inspect/toggle-view-mode inspect/toggle-view-mode inspect/toggle-view-mode :view-mode))

  (is+ ["  ●normal table object ●pretty sort-maps"]
       (-> (repeat 10 [1 2]) (inspect {:pretty-print true}) render (section "View mode"))))

(deftest pretty-print-map-test
  (testing "in :pretty view-mode are pretty printed"
    (is+ {"Contents"
          ["  " [:value ":a" 1] " = " [:value "0" 2] [:newline]
           "  " [:value ":bb" 3] " = " [:value "\"000\"" 4] [:newline]
           "  " [:value ":ccc" 5] " = " [:value "[]" 6] [:newline]
           "  " [:value ":d" 7] " = "
           [:value (str "[{:a 0, :bb \"000\", :ccc [[]]}\n"
                        "        {:a -1, :bb \"111\", :ccc [1]}\n"
                        "        {:a 2, :bb \"222\", :ccc [1 2]}]") 8]]

          "View mode"
          ["  ●normal object ●pretty sort-maps"]}
         (-> {:a 0
              :bb "000"
              :ccc []
              :d [{:a 0 :bb "000" :ccc [[]]}
                  {:a -1 :bb "111" :ccc [1]}
                  {:a 2 :bb "222" :ccc [1 2]}]}
             inspect
             (set-pretty-print true)
             render
             group-sections))))

(deftest pretty-print-map-in-object-view-test
  (testing "in :object view mode + :pretty, Value: is printed regularly"
    (is+ (matchers/prefix
          ["Class: " [:value "clojure.lang.PersistentArrayMap" 0] [:newline]
           "Value: "
           [:value "{:a 0, :bb \"000\", :ccc [], :d [{:a 0, :bb \"000\", :ccc [[]]} {:a -1, :bb \"111\", :ccc [1]} {:a 2, :bb \"222\", :ccc [1 2]}]}" 1]])
         (-> {:a 0
              :bb "000"
              :ccc []
              :d [{:a 0 :bb "000" :ccc [[]]}
                  {:a -1 :bb "111" :ccc [1]}
                  {:a 2 :bb "222" :ccc [1 2]}]}
             inspect
             (inspect/set-view-mode :object)
             (set-pretty-print true)
             render
             header))))

(deftest pretty-print-seq-of-maps-test
  (testing "in :pretty view-mode maps seqs of maps are pretty printed"
    (is+ {"Contents"
          ["  0. "
           [:value (str "{:a 0,\n      :bb \"000\",\n      :ccc (),\n      "
                        ":d\n      ({:a 0, :bb \"000\", :ccc ()}\n       "
                        "{:a -1, :bb \"111\", :ccc (1)}\n       {:a -2, :bb "
                        "\"222\", :ccc (2 1)}\n       {:a -3, :bb \"333\", "
                        ":ccc (3 2 1)}\n       {:a -4, :bb \"444\", :ccc "
                        "(4 3 2 1)})}") 1]
           [:newline]
           "  1. "
           [:value (str "{:a -1,\n      :bb \"111\",\n      :ccc (1),\n      "
                        ":d\n      ({:a 0, :bb \"000\", :ccc ()}\n       "
                        "{:a -1, :bb \"111\", :ccc (1)}\n       {:a -2, :bb "
                        "\"222\", :ccc (2 1)}\n       {:a -3, :bb \"333\", "
                        ":ccc (3 2 1)}\n       {:a -4, :bb \"444\", "
                        ":ccc (4 3 2 1)})}") 2]]

          "View mode"
          ["  ●normal table object ●pretty sort-maps"]}
         (-> (for [i (range 2)]
               {:a (- i)
                :bb (str i i i)
                :ccc (range i 0 -1)
                :d (for [i (range 5)]
                     {:a (- i)
                      :bb (str i i i)
                      :ccc (range i 0 -1)})})
             inspect
             (set-pretty-print true)
             render
             group-sections))))

(deftest pretty-print-map-as-key-test
  (testing "in :pretty view-mode maps that contain maps as keys are pretty printed"
    (is+ ["  "
          [:value (str "{:a 0,\n   :bb \"000\",\n   :ccc [],\n   :d\n   "
                       "[{:a 0, :bb \"000\", :ccc []}\n    {:a -1, "
                       ":bb \"111\", :ccc [1]}\n    {:a -2, :bb \"222\", "
                       ":ccc [2 1]}\n    {:a -3, :bb \"333\", :ccc [3 2 1]}"
                       "\n    {:a -4, :bb \"444\", :ccc [4 3 2 1]}]}") 1]
          [:newline] "  =" [:newline] "  "
          [:value (str "{:a -1,\n   :bb \"111\",\n   :ccc [1],\n   "
                       ":d\n   [{:a 0, :bb \"000\", :ccc []}\n    "
                       "{:a -1, :bb \"111\", :ccc [1]}\n    {:a -2, "
                       ":bb \"222\", :ccc [2 1]}\n    {:a -3, :bb "
                       "\"333\", :ccc [3 2 1]}\n    {:a -4, :bb "
                       "\"444\", :ccc [4 3 2 1]}]}") 2]]
         (-> {{:a 0
               :bb "000"
               :ccc []
               :d [{:a 0 :bb "000" :ccc []}
                   {:a -1 :bb "111" :ccc [1]}
                   {:a -2 :bb "222" :ccc [2 1]}
                   {:a -3 :bb "333" :ccc [3 2 1]}
                   {:a -4 :bb "444" :ccc [4 3 2 1]}]}
              {:a -1
               :bb "111"
               :ccc [1]
               :d [{:a 0 :bb "000" :ccc []}
                   {:a -1 :bb "111" :ccc [1]}
                   {:a -2 :bb "222" :ccc [2 1]}
                   {:a -3 :bb "333" :ccc [3 2 1]}
                   {:a -4 :bb "444" :ccc [4 3 2 1]}]}}
             inspect
             (set-pretty-print true)
             render
             contents-section))))

(deftest pretty-print-seq-of-map-as-key-test
  (testing "in :pretty view-mode maps that contain seq of maps as a keys are pretty printed"
    (is+ ["  "
          [:value (str "[{:a 0,\n    :bb \"000\",\n    :ccc [],\n    :d\n    "
                       "[{:a 0, :bb \"000\", :ccc [[]]}\n     {:a -1, :bb \"111\", "
                       ":ccc [1]}\n     {:a 2, :bb \"222\", :ccc [1 2]}]}]") 1]
          [:newline] "  =" [:newline] "  "
          [:value (str "{:a 0,\n   :bb \"000\",\n   :ccc [],\n   :d\n   "
                       "[{:a 0, :bb \"000\", :ccc [[]]}\n    {:a -1, "
                       ":bb \"111\", :ccc [1]}\n    {:a 2, :bb \"222\", "
                       ":ccc [1 2]}]}") 2]]
         (-> {[{:a 0
                :bb "000"
                :ccc []
                :d [{:a 0 :bb "000" :ccc [[]]}
                    {:a -1 :bb "111" :ccc [1]}
                    {:a 2 :bb "222" :ccc [1 2]}]}]
              {:a 0
               :bb "000"
               :ccc []
               :d [{:a 0 :bb "000" :ccc [[]]}
                   {:a -1 :bb "111" :ccc [1]}
                   {:a 2 :bb "222" :ccc [1 2]}]}}
             inspect
             (set-pretty-print true)
             render
             contents-section))))

(deftest sort-maps-test
  (testing "with :sort-map-keys enabled, map keys are sorted"
    (is+ {"Contents"
          (matchers/prefix
           ["  " [:value "0" pos?] " = " [:value "0" pos?] [:newline]
            "  " [:value "1" pos?] " = " [:value "1" pos?] [:newline]
            "  " [:value "2" pos?] " = " [:value "2" pos?] [:newline]
            "  " [:value "3" pos?] " = " [:value "3" pos?] [:newline]])

          "View mode"
          ["  ●normal object pretty ●sort-maps"]}
         (-> (zipmap (range 100) (range 100))
             inspect
             (inspect/refresh {:sort-maps true})
             render
             group-sections)))

  (testing "works if map is smaller than page size"
    (is+ ["  " [:value "0" pos?] " = " [:value "0" pos?] [:newline]
          "  " [:value "1" pos?] " = " [:value "1" pos?] [:newline]
          "  " [:value "2" pos?] " = " [:value "2" pos?] [:newline]
          "  " [:value "3" pos?] " = " [:value "3" pos?] [:newline]
          "  " [:value "4" pos?] " = " [:value "4" pos?]]
         (-> (zipmap (range 5) (range 5))
             inspect
             (inspect/refresh {:sort-maps true, :page-size 100})
             render
             contents-section)))

  (testing "doesn't fail if keys are non-comparable"
    (is (-> {(byte-array 1) 1 (byte-array 2) 2}
            inspect
            (inspect/refresh {:sort-maps true})
            render
            contents-section))))

(deftest compact-keywords-test
  (testing "when :pov-ns is passed, use it to compact qualified keywords"
    (is+ ["  " [:value "::foo" pos?] " = " [:value "1" pos?] [:newline]
          "  " [:value "::str/bar" pos?] " = " [:value "2" pos?] [:newline]
          "  " [:value "::misc/baz" pos?] " = " [:value "3" pos?]]
         (-> {::foo 1
              ::str/bar 2
              :orchard.misc/baz 3}
             (inspect {:pov-ns 'orchard.inspect-test})
             render contents-section))))

(deftest tap-test
  (testing "tap-current-value"
    (let [proof (atom [])
          test-tap-handler (fn [x]
                             (swap! proof conj x))
          sleep (long
                 (if (System/getenv "CI")
                   200
                   100))]

      (add-tap test-tap-handler)

      (-> (inspect {:a {:b 1}})
          (inspect/tap-current-value)
          (inspect/down 2)
          (inspect/tap-current-value)
          (inspect/down 1)
          (inspect/tap-current-value))

      (let [expected [{:a {:b 1}}
                      {:b 1}
                      :b]
            tries (atom 0)]

        (while (and (not= expected @proof)
                    (< @tries 1000))
          (Thread/sleep sleep)
          (swap! tries inc))

        (is (= expected @proof)))

      (remove-tap test-tap-handler)))

  (testing "tap-indexed"
    (let [proof (atom [])
          test-tap-handler (fn [x]
                             (swap! proof conj x))
          sleep (long
                 (if (System/getenv "CI")
                   200
                   100))]

      (add-tap test-tap-handler)

      (-> (inspect {:a {:b 1}})
          (inspect/tap-indexed 1)
          (inspect/tap-indexed 2)
          (inspect/down 2)
          (inspect/tap-indexed 1)
          (inspect/tap-indexed 2))

      (let [expected [:a
                      {:b 1}
                      :b
                      1]
            tries (atom 0)]

        (while (and (not= expected @proof)
                    (< @tries 1000))
          (Thread/sleep sleep)
          (swap! tries inc))

        (is (= expected @proof)))

      (remove-tap test-tap-handler))))

(deftest private-field-access-test
  (testing "Inspection of private fields is attempted (may fail depending on the JDK and the module of the given class)"
    (if (< java-api-version 17)
      (do
        (is+ nil (-> (inspect 2) render (section "Private static fields")))
        (is+ (matchers/embeds [[:value "serialVersionUID" number?]])
             (-> (inspect 2) render (section "Static fields"))))

      ;; the image lists the JDK's unregistered private fields on some platforms only
      (let [fields (-> 2 inspect render (section "Private static fields"))]
        (is (or (nil? fields)
                (= ["serialVersionUID" "<non-inspectable value>"]
                   [(second (nth fields 1)) (second (nth fields 3))])))))))

(deftest analytics-test
  (testing "analytics is not shown by default"
    (is+ nil (-> (range 100) inspect render (section "Analytics"))))

  (testing "analytics is shown when requested"
    (is+ ["  " [:value ":count" pos?] " = " [:value "100" pos?] [:newline]
          "  " [:value ":types" pos?] " = " [:value "{java.lang.Long 100}" pos?] [:newline]
          "  " [:value ":numbers" pos?] " = " [:value "{:n 100, :zeros 1, :max 99, :min 0, :mean 49.5}" pos?]]
         (-> (range 100) inspect inspect/display-analytics render (section "Analytics"))))

  (testing "cutoff is customizable and limits number of values analytics processes"
    (is+ (matchers/prefix
          ["  " [:value ":cutoff?" pos?] " = " [:value "true" pos?] [:newline]
           "  " [:value ":count" pos?] " = " [:value "10" pos?] [:newline]
           "  " [:value ":types" pos?] " = " [:value "{java.lang.Long 10}" pos?]])
         (-> (range 100)
             inspect
             (inspect/refresh {:analytics-size-cutoff 10})
             inspect/display-analytics
             render
             (section "Analytics")))))

(def data1 [{:tea/type "Jinxuan Oolong"
             :tea/color "Green"
             :tea/region "Alishan"
             :aliases ["Milky Wulong" "Jinxuan"]
             :temperature 80}
            {:tea/type "Dong Ding"
             :tea/region "Nantou"
             :aliases ["Frozen summit" "Dongti" "Dong ding wulong"]}
            "same string"
            3])

(def data2 [{:tea/type "Jinxuan Wulong"
             :tea/color "Green"
             :tea/region "Alishan"
             :aliases ["Milky Wulong" "金宣" "Jinxuan"]
             :temperature 75}
            {:tea/type "Dong Ding"
             :tea/region "Nantou"
             :aliases ["Frozen summit" "Dongti" "Dong ding wulong"]
             :temperature 85}
            "same string"
            4])

(deftest diff-test
  (is+ {"Diff contents"
        ["  0. " [:value "#≠{:tea/type #±[\"Jinxuan Oolong\" ~~ \"Jinxuan Wulong\"], :tea/color \"Green\", :tea/region \"Alishan\", :aliases #≠[\"Milky Wulong\" #±[\"Jinxuan\" ~~ \"金宣\"] #±[ ~~ \"Jinxuan\"]], :temperature #±[80 ~~ 75]}" pos?] [:newline]
         "  1. " [:value "#≠{:tea/type \"Dong Ding\", :tea/region \"Nantou\", :aliases [\"Frozen summit\" \"Dongti\" \"Dong ding wulong\"], :temperature #±[ ~~ 85]}" pos?] [:newline]
         "  2. " [:value "\"same string\"" pos?] [:newline]
         "  3. " [:value "#±[3 ~~ 4]" pos?]]

        "View mode"
        ["  ●normal object pretty sort-maps only-diff"]}
       (-> (inspect/diff data1 data2)
           inspect
           render
           group-sections))

  (is+ ["  " [:value ":tea/type" pos?] " = " [:value "#±[\"Jinxuan Oolong\" ~~ \"Jinxuan Wulong\"]" pos?] [:newline]
        "  " [:value ":tea/color" pos?] " = " [:value "\"Green\"" pos?] [:newline]
        "  " [:value ":tea/region" pos?] " = " [:value "\"Alishan\"" pos?] [:newline]
        "  " [:value ":aliases" pos?] " = " [:value "#≠[\"Milky Wulong\" #±[\"Jinxuan\" ~~ \"金宣\"] #±[ ~~ \"Jinxuan\"]]" pos?] [:newline]
        "  " [:value ":temperature" pos?] " = " [:value "#±[80 ~~ 75]" pos?]]
       (-> (inspect/diff data1 data2)
           inspect
           (inspect/down 1)
           render
           (section "Diff contents")))

  (is+ ["   Left: " [:value "\"Jinxuan Oolong\"" pos?] [:newline]
        "  Right: " [:value "\"Jinxuan Wulong\"" pos?]]
       (-> (inspect/diff data1 data2)
           inspect
           (inspect/down 1)
           (inspect/down 2)
           render
           (section "Diff")))

  (is+ ["  0. " [:value "\"Milky Wulong\"" pos?] [:newline]
        "  1. " [:value "#±[\"Jinxuan\" ~~ \"金宣\"]" pos?] [:newline]
        "  2. " [:value "#±[ ~~ \"Jinxuan\"]" 3]]
       (-> (inspect/diff data1 data2)
           inspect
           (inspect/down 1)
           (inspect/down 8)
           render
           (section "Diff contents")))

  (testing "diff sets"
    ;; Complicated regexp is used to match because the order of elements in a
    ;; set is not predictable.
    (is+ ["  " [:value ":a" pos?] " = " [:value #"#≠#\{((2|3|#±\[1 ~~ 4\]) ?){3}\}" pos?]]
         (-> (inspect/diff {:a #{1 2 3}} {:a #{2 3 4}})
             inspect
             render
             (section "Diff contents"))))

  (testing "in :only-diff mode, render only differing subvalues"
    (is+ {"Diff contents"
          ["  0. " [:value "#≠{:tea/type #±[\"Jinxuan Oolong\" ~~ \"Jinxuan Wulong\"], :aliases #≠[ #±[\"Jinxuan\" ~~ \"金宣\"] #±[ ~~ \"Jinxuan\"]], :temperature #±[80 ~~ 75]}" pos?] [:newline]
           "  1. " [:value "#≠{:temperature #±[ ~~ 85]}" pos?] [:newline]
           "  2. " [:value "" pos?] [:newline]
           "  3. " [:value "#±[3 ~~ 4]" pos?]]

          "View mode"
          ["  ●normal object pretty sort-maps ●only-diff"]}
         (-> (inspect/diff data1 data2)
             (inspect {:only-diff true})
             render
             group-sections))

    (is+ ["  " [:value ":tea/type" pos?] " = " [:value "#±[\"Jinxuan Oolong\" ~~ \"Jinxuan Wulong\"]" pos?] [:newline]
          "  " [:value ":aliases" pos?] " = " [:value "#≠[ #±[\"Jinxuan\" ~~ \"金宣\"] #±[ ~~ \"Jinxuan\"]]" pos?] [:newline]
          "  " [:value ":temperature" pos?] " = " [:value "#±[80 ~~ 75]" pos?]]
         (-> (inspect/diff data1 data2)
             (inspect {:only-diff true})
             (inspect/down 1)
             render
             (section "Diff contents"))))

  (testing "works with :pretty-print"
    (is+ ["  0. " [:value "#≠{:tea/type #±[\"Jinxuan Oolong\" ~~ \"Jinxuan Wulong\"],
        :aliases #≠[ #±[\"Jinxuan\" ~~ \"金宣\"] #±[ ~~ \"Jinxuan\"]],
        :temperature #±[80 ~~ 75]}" pos?] [:newline]
          "  1. " [:value "#≠{:temperature #±[ ~~ 85]}" pos?] [:newline]
          "  2. " [:value "" pos?] [:newline]
          "  3. " [:value "#±[3 ~~ 4]" pos?]]
         (-> (inspect/diff data1 data2)
             (inspect {:only-diff true, :pretty-print true})
             render
             (section "Diff contents")))))
