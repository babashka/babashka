(ns gaka.core-test
  (:use gaka.core
        clojure.test))

(defmacro =? [& body]
  `(are [x# y#] (= x# y#)
        ~@body))

(deftest test-flatten
  (let [flatten-seqs #'gaka.core/flatten-seqs
        flatten-maps #'gaka.core/flatten-maps
        flatten-keyvals #'gaka.core/flatten-keyvals]
   (=? (flatten-seqs [1 '(2 3)])
       [1 2 3])

   (=? (flatten-seqs [1 '(2 [3 (4)])])
       [1 2 [3 '(4)]])

   (=? (flatten-seqs [1 '(2 (3 (4)))])
       [1 2 3 4])

   (=? (flatten-seqs [1 '(2 3 [4])])
       [1 2 3 [4]])

   (=? (flatten-maps [1 2 {3 4}])
       [1 2 3 4])

   (=? (flatten-maps [1 {2 3 4 5} 6])
       [1 2 3 4 5 6])

   (=? (flatten-keyvals [1 '(2 {3 4} 5)])
       [1 2 3 4 5])

   (=? (flatten-keyvals [1 [2 {3 4}]])
       [1 [2 {3 4}]])

   (=? (flatten-keyvals [1 '([2] 3 [4] {5 6})])
       [1 [2] 3 [4] 5 6])
))

(deftest test-compile*
  (=? (compile* []  [:a])
      [{:selector ["a"]
        :keyvals []}]

      (compile* []  [:a :color :red])
      [{:selector ["a"]
        :keyvals [:color :red]}]

      (compile* []  [:a {:color :red}])
      [{:selector ["a"]
        :keyvals [:color :red]}]

      (compile* []  [:a :color :blue {:color :red}])
      [{:selector ["a"]
        :keyvals [:color :blue :color :red]}]

      (compile* []  [:a {:color :red} :color :blue])
      [{:selector ["a"]
        :keyvals [:color :red :color :blue]}]

      (compile* [] [:a [:img :border :none]])
      [{:selector ["a"]
        :keyvals []}
       {:selector ["a" "img"]
        :keyvals [:border :none]}]

      (compile* [] [:div [:a [:img :border :none]]])
      [{:selector ["div"]
        :keyvals []}
       {:selector ["div" "a"]
        :keyvals []}
       {:selector ["div" "a" "img"]
        :keyvals [:border :none]}]

      (compile* [] [:div (list :border :none)])
      [{:selector ["div"]
        :keyvals [:border :none]}]

      (compile* [] [:div ["a, img" :border :none]])
      [{:selector ["div"]
        :keyvals []}
       {:selector ["div" "a"]
        :keyvals [:border :none]}
       {:selector ["div" "img"]
        :keyvals [:border :none]}]))

(deftest test-mixins
  (=? (let [mixin (list :color :red)]
        (compile* [] [:a mixin]))
      [{:selector ["a"]
        :keyvals [:color :red]}]

      (let [a (list [:a :color :red])]
        (compile* [] [:div a]))
      [{:selector ["div"]
        :keyvals []}
       {:selector ["div" "a"]
        :keyvals [:color :red]}]

      (let [mixin {:color :red}]
        (compile* [] [:div mixin]))
      [{:selector ["div"]
        :keyvals [:color :red]}]

      (let [mixin {:color :red}]
        (compile* [] [:div [:a mixin]]))
      [{:selector ["div"]
        :keyvals []}
       {:selector ["div" "a"]
        :keyvals [:color :red]}]

      (let [mixin (list :color :red)
            els (list [:a mixin] [:span mixin])]
        (compile* [] [:div els]))
      [{:selector ["div"]
        :keyvals []}
       {:selector ["div" "a"]
        :keyvals [:color :red]}
       {:selector ["div" "span"]
        :keyvals [:color :red]}]

      (let [mixin (list :color :red {:border :none})
            els (list [:a mixin] :color :blue [:span mixin])]
        (compile* [] [:div els]))
      [{:selector ["div"]
        :keyvals [:color :blue]}
       {:selector ["div" "a"]
        :keyvals [:color :red :border :none]}
       {:selector ["div" "span"]
        :keyvals [:color :red :border :none]}]))

(deftest test-render-rule
  (=? (render-rule {:selector ["a"] :keyvals [:color :red]})
      "a {\n  color: red;}\n\n"

      (render-rule {:selector ["a"] :keyvals [:color :red :border :none]})
      "a {\n  color: red;\n  border: none;}\n\n"

      (render-rule {:selector ["a" "img"] :keyvals [:border :none]})
      "  a img {\n    border: none;}\n\n"))

(deftest test-css
  (=? (css nil)
      ""
      
      (css [:a :color :red [:img :border :none]])
      "a {\n  color: red;}\n\n  a img {\n    border: none;}\n\n"

      (css [:a {:color :red} [:img {:border :none}]])
      "a {\n  color: red;}\n\n  a img {\n    border: none;}\n\n"

      (css [:a :color :red [:img :border :none] :font-style :italic])
      "a {\n  color: red;\n  font-style: italic;}\n\n  a img {\n    border: none;}\n\n"

      (css [:body
            :padding 0
            [:div#foo
             [:a :color :red]]
            :margin 0
            [:div#bar
             [:a :color :blue]]
            :border 0])
      "body {\n  padding: 0;\n  margin: 0;\n  border: 0;}\n\n    body div#foo a {\n      color: red;}\n\n    body div#bar a {\n      color: blue;}\n\n"))

(deftest test-css-no-indent
  (binding [gaka.core/*print-indent* false]
   (=? (css nil)
       ""
      
       (css [:a :color :red [:img :border :none]])
       "a {\ncolor: red;}\n\na img {\nborder: none;}\n\n"

       (css [:a {:color :red} [:img {:border :none}]])
       "a {\ncolor: red;}\n\na img {\nborder: none;}\n\n"

       (css [:a :color :red [:img :border :none] :font-style :italic])
       "a {\ncolor: red;\nfont-style: italic;}\n\na img {\nborder: none;}\n\n"

       (css [:body
             :padding 0
             [:div#foo
              [:a :color :red]]
             :margin 0
             [:div#bar
              [:a :color :blue]]
             :border 0])
       "body {\npadding: 0;\nmargin: 0;\nborder: 0;}\n\nbody div#foo a {\ncolor: red;}\n\nbody div#bar a {\ncolor: blue;}\n\n")))

(deftest test-inline-css
  (=? (inline-css :color :red :border 1)
      "color: red; border: 1;")
  (is
   (re-find #"^(color: red; border: 1;|border: 1; color: red;)$"
            (inline-css {:color :red :border 1}))))

