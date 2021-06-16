(ns hiccup2.core-test
  (:require [clojure.test :refer :all]
            [hiccup2.core :refer :all]
            [hiccup.util :as util]))

(deftest return-types
  #_(testing "html returns a RawString"
    (is (util/raw-string? (html [:div]))))
  (testing "converting to string"
    (= (str (html [:div])) "<div></div>")))

(deftest tag-names
  (testing "basic tags"
    (is (= (str (html [:div])) "<div></div>"))
    (is (= (str (html ["div"])) "<div></div>"))
    (is (= (str (html ['div])) "<div></div>")))
  (testing "tag syntax sugar"
    (is (= (str (html [:div#foo])) "<div id=\"foo\"></div>"))
    (is (= (str (html [:div.foo])) "<div class=\"foo\"></div>"))
    (is (= (str (html [:div.foo (str "bar" "baz")]))
           "<div class=\"foo\">barbaz</div>"))
    (is (= (str (html [:div.a.b])) "<div class=\"a b\"></div>"))
    (is (= (str (html [:div.a.b.c])) "<div class=\"a b c\"></div>"))
    (is (= (str (html [:div#foo.bar.baz]))
           "<div class=\"bar baz\" id=\"foo\"></div>"))))

(deftest tag-contents
  (testing "empty tags"
    (is (= (str (html [:div])) "<div></div>"))
    (is (= (str (html [:h1])) "<h1></h1>"))
    (is (= (str (html [:script])) "<script></script>"))
    (is (= (str (html [:text])) "<text></text>"))
    (is (= (str (html [:a])) "<a></a>"))
    (is (= (str (html [:iframe])) "<iframe></iframe>"))
    (is (= (str (html [:title])) "<title></title>"))
    (is (= (str (html [:section])) "<section></section>"))
    (is (= (str (html [:select])) "<select></select>"))
    (is (= (str (html [:object])) "<object></object>"))
    (is (= (str (html [:video])) "<video></video>")))
  (testing "void tags"
    (is (= (str (html [:br])) "<br />"))
    (is (= (str (html [:link])) "<link />"))
    (is (= (str (html [:colgroup {:span 2}])) "<colgroup span=\"2\"></colgroup>"))
    (is (= (str (html [:colgroup [:col]])) "<colgroup><col /></colgroup>")))
  (testing "tags containing text"
    (is (= (str (html [:text "Lorem Ipsum"])) "<text>Lorem Ipsum</text>")))
  (testing "contents are concatenated"
    (is (= (str (html [:body "foo" "bar"])) "<body>foobar</body>"))
    (is (= (str (html [:body [:p] [:br]])) "<body><p></p><br /></body>")))
  (testing "seqs are expanded"
    (is (= (str (html [:body (list "foo" "bar")])) "<body>foobar</body>"))
    (is (= (str (html (list [:p "a"] [:p "b"]))) "<p>a</p><p>b</p>")))
  (testing "keywords are turned into strings"
    (is (= (str (html [:div :foo])) "<div>foo</div>")))
  (testing "vecs don't expand - error if vec doesn't have tag name"
    (is (thrown? IllegalArgumentException
                 (html (vector [:p "a"] [:p "b"])))))
  (testing "tags can contain tags"
    (is (= (str (html [:div [:p]])) "<div><p></p></div>"))
    (is (= (str (html [:div [:b]])) "<div><b></b></div>"))
    (is (= (str (html [:p [:span [:a "foo"]]]))
           "<p><span><a>foo</a></span></p>"))))

(deftest tag-attributes
  (testing "tag with blank attribute map"
    (is (= (str (html [:xml {}])) "<xml></xml>")))
  (testing "tag with populated attribute map"
    (is (= (str (html [:xml {:a "1", :b "2"}])) "<xml a=\"1\" b=\"2\"></xml>"))
    (is (= (str (html [:img {"id" "foo"}])) "<img id=\"foo\" />"))
    (is (= (str (html [:img {'id "foo"}])) "<img id=\"foo\" />"))
    (is (= (str (html [:xml {:a "1", 'b "2", "c" "3"}]))
           "<xml a=\"1\" b=\"2\" c=\"3\"></xml>")))
  (testing "attribute values are escaped"
    (is (= (str (html [:div {:id "\""}])) "<div id=\"&quot;\"></div>")))
  (testing "boolean attributes"
    (is (= (str (html [:input {:type "checkbox" :checked true}]))
           "<input checked=\"checked\" type=\"checkbox\" />"))
    (is (= (str (html [:input {:type "checkbox" :checked false}]))
           "<input type=\"checkbox\" />")))
  (testing "nil attributes"
    (is (= (str (html [:span {:class nil} "foo"]))
           "<span>foo</span>")))
  (testing "vector attributes"
    (is (= (str (html [:span {:class ["bar" "baz"]} "foo"]))
           "<span class=\"bar baz\">foo</span>"))
    (is (= (str (html [:span {:class ["baz"]} "foo"]))
           "<span class=\"baz\">foo</span>"))
    (is (= (str (html [:span {:class "baz bar"} "foo"]))
           "<span class=\"baz bar\">foo</span>")))
  (testing "map attributes"
    (is (= (str (html [:span {:style {:color "red" :opacity "100%"}} "foo"]))
           "<span style=\"color:red;opacity:100%;\">foo</span>")))
  (testing "resolving conflicts between attributes in the map and tag"
    (is (= (str (html [:div.foo {:class "bar"} "baz"]))
           "<div class=\"foo bar\">baz</div>"))
    (is (= (str (html [:div.foo {:class ["bar"]} "baz"]))
           "<div class=\"foo bar\">baz</div>"))
    (is (= (str (html [:div#bar.foo {:id "baq"} "baz"]))
           "<div class=\"foo\" id=\"baq\">baz</div>"))))

(deftest compiled-tags
  (testing "tag content can be vars"
    (is (= (let [x "foo"] (str (html [:span x]))) "<span>foo</span>")))
  (testing "tag content can be forms"
    (is (= (str (html [:span (str (+ 1 1))])) "<span>2</span>"))
    (is (= (str (html [:span ({:foo "bar"} :foo)])) "<span>bar</span>")))
  (testing "attributes can contain vars"
    (let [x "foo"]
      (is (= (str (html [:xml {:x x}])) "<xml x=\"foo\"></xml>"))
      (is (= (str (html [:xml {x "x"}])) "<xml foo=\"x\"></xml>"))
      (is (= (str (html [:xml {:x x} "bar"])) "<xml x=\"foo\">bar</xml>"))))
  (testing "attributes are evaluated"
    (is (= (str (html [:img {:src (str "/foo" "/bar")}]))
           "<img src=\"/foo/bar\" />"))
    (is (= (str (html [:div {:id (str "a" "b")} (str "foo")]))
           "<div id=\"ab\">foo</div>")))
  (testing "type hints"
    (let [string "x"]
      (is (= (str (html [:span ^String string])) "<span>x</span>"))))
  (testing "optimized forms"
    (is (= (str (html [:ul (for [n (range 3)]
                             [:li n])]))
           "<ul><li>0</li><li>1</li><li>2</li></ul>"))
    (is (= (str (html [:div (if true
                              [:span "foo"]
                              [:span "bar"])]))
           "<div><span>foo</span></div>")))
  (testing "values are evaluated only once"
    (let [times-called (atom 0)
          foo #(swap! times-called inc)]
      (html [:div (foo)])
      (is (= @times-called 1)))))

(deftest render-modes
  (testing "closed tag"
    (is (= (str (html [:p] [:br])) "<p></p><br />"))
    (is (= (str (html {:mode :xhtml} [:p] [:br])) "<p></p><br />"))
    (is (= (str (html {:mode :html} [:p] [:br])) "<p></p><br>"))
    (is (= (str (html {:mode :xml} [:p] [:br])) "<p /><br />"))
    (is (= (str (html {:mode :sgml} [:p] [:br])) "<p><br>")))
  (testing "boolean attributes"
    (is (= (str (html {:mode :xml} [:input {:type "checkbox" :checked true}]))
           "<input checked=\"checked\" type=\"checkbox\" />"))
    (is (= (str (html {:mode :sgml} [:input {:type "checkbox" :checked true}]))
           "<input checked type=\"checkbox\">")))
  (testing "laziness and binding scope"
    (is (= (str (html {:mode :sgml} [:html [:link] (list [:link])]))
           "<html><link><link></html>")))
  (testing "function binding scope"
    (let [f #(html [:p "<>" [:br]])]
      (is (= (str (html (f))) "<p>&lt;&gt;<br /></p>"))
      (is (= (str (html {:escape-strings? false} (f))) "<p><><br /></p>"))
      (is (= (str (html {:mode :html} (f))) "<p>&lt;&gt;<br></p>"))
      (is (= (str (html {:escape-strings? false, :mode :html} (f))) "<p><><br></p>")))))

(deftest auto-escaping
  (testing "literals"
    (is (= (str (html "<>")) "&lt;&gt;"))
    (is (= (str (html :<>)) "&lt;&gt;"))
    (is (= (str (html ^String (str "<>"))) "&lt;&gt;"))
    (is (= (str (html {} {"<a>" "<b>"})) "{&quot;&lt;a&gt;&quot; &quot;&lt;b&gt;&quot;}"))
    (is (= (str (html #{"<>"})) "#{&quot;&lt;&gt;&quot;}"))
    (is (= (str (html 1)) "1"))
    (is (= (str (html ^Number (+ 1 1))) "2")))
  (testing "non-literals"
    (is (= (str (html (list [:p "<foo>"] [:p "<bar>"])))
           "<p>&lt;foo&gt;</p><p>&lt;bar&gt;</p>"))
    (is (= (str (html ((constantly "<foo>")))) "&lt;foo&gt;"))
    (is (= (let [x "<foo>"] (str (html x))) "&lt;foo&gt;")))
  (testing "optimized forms"
    (is (= (str (html (if true :<foo> :<bar>))) "&lt;foo&gt;"))
    (is (= (str (html (for [x [:<foo>]] x))) "&lt;foo&gt;")))
  (testing "elements"
    (is (= (str (html [:p "<>"])) "<p>&lt;&gt;</p>"))
    (is (= (str (html [:p :<>])) "<p>&lt;&gt;</p>"))
    (is (= (str (html [:p {} {"<foo>" "<bar>"}]))
           "<p>{&quot;&lt;foo&gt;&quot; &quot;&lt;bar&gt;&quot;}</p>"))
    (is (= (str (html [:p {} #{"<foo>"}]))
           "<p>#{&quot;&lt;foo&gt;&quot;}</p>"))
    (is (= (str (html [:p {:class "<\">"}]))
           "<p class=\"&lt;&quot;&gt;\"></p>"))
    (is (= (str (html [:p {:class ["<\">"]}]))
           "<p class=\"&lt;&quot;&gt;\"></p>"))
    (is (= (str (html [:ul [:li "<foo>"]]))
           "<ul><li>&lt;foo&gt;</li></ul>")))
  (testing "raw strings"
    #_(is (= (str (html (util/raw-string "<foo>"))) "<foo>"))
    (is (= (str (html [:p (util/raw-string "<foo>")])) "<p><foo></p>"))
    (is (= (str (html (html [:p "<>"]))) "<p>&lt;&gt;</p>"))
    (is (= (str (html [:ul (html [:li "<>"])])) "<ul><li>&lt;&gt;</li></ul>"))))

(deftest html-escaping
  (testing "precompilation"
    (is (= (str (html {:escape-strings? true}  [:p "<>"])) "<p>&lt;&gt;</p>"))
    (is (= (str (html {:escape-strings? false} [:p "<>"])) "<p><></p>")))
  (testing "dynamic generation"
    (let [x [:p "<>"]]
      (is (= (str (html {:escape-strings? true}  x)) "<p>&lt;&gt;</p>"))
      (is (= (str (html {:escape-strings? false} x)) "<p><></p>"))))
  (testing "attributes"
    (is (= (str (html {:escape-strings? true}  [:p {:class "<>"}]))
           "<p class=\"&lt;&gt;\"></p>"))
    (is (= (str (html {:escape-strings? false} [:p {:class "<>"}]))
           "<p class=\"&lt;&gt;\"></p>")))
  (testing "raw strings"
    (is (= (str (html {:escape-strings? true}  [:p (util/raw-string "<>")]))
           "<p><></p>"))
    (is (= (str (html {:escape-strings? false} [:p (util/raw-string "<>")]))
           "<p><></p>"))
    #_(is (= (str (html {:escape-strings? true}  [:p (raw "<>")]))
           "<p><></p>"))
    #_(is (= (str (html {:escape-strings? false} [:p (raw "<>")]))
           "<p><></p>"))))
