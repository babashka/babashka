(ns clj-stacktrace.core-test
  (:use clojure.test)
  (:use clj-stacktrace.core)
  (:use clj-stacktrace.utils))

(def cases
  [["foo.bar$biz__123" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "biz"
     :file "bar.clj" :line 456 :anon-fn false}]

   ["foo.bar$biz_bat__123" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "biz-bat"
     :file "bar.clj" :line 456 :anon-fn false}]

   ["foo.bar$biz_bat_QMARK___448" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "biz-bat?"
     :file "bar.clj" :line 456 :anon-fn false}]

   ["foo.bar$biz_bat_QMARK___448$fn__456" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "biz-bat?"
     :file "bar.clj" :line 456 :anon-fn true}]

   ["foo.bar$repl$fn__5629.invoke" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "repl"
     :file "bar.clj" :line 456 :anon-fn true}]

   ["foo.bar$repl$read_eval_print__5624" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar" :fn "repl"
     :file "bar.clj" :line 456 :anon-fn true}]

   ["foo.bar$biz__123$fn__456" "invoke" "bar.clj" 789
    {:clojure true :ns "foo.bar" :fn "biz"
     :file "bar.clj" :line 789 :anon-fn true}]

   ["foo.bar_bat$biz__123" "invoke" "bar.clj" 456
    {:clojure true :ns "foo.bar-bat" :fn "biz"
     :file "bar.clj" :line 456 :anon-fn false}]

   ["user$eval__345" "invoke" nil -1
    {:clojure true :ns "user" :fn "eval"
     :file nil :line nil :anon-fn false}]

   ["lamina.core.observable.ConstantObservable" "message" "observable.clj" 198
    {:clojure true :ns "lamina.core.observable"
     :fn "lamina.core.observable.ConstantObservable"
     :file "observable.clj" :line 198 :anon-fn false}]
   
   ["clojure.lang.Var" "invoke" "Var.java" 123
    {:java true :class "clojure.lang.Var" :method "invoke"
     :file "Var.java" :line 123}]

   ["clojure.proxy.space.SomeClass" "someMethod" "SomeClass.java" 123
    {:java true :class "clojure.proxy.space.SomeClass" :method "someMethod"
     :file "SomeClass.java" :line 123}]

   ["some.space.SomeClass" "someMethod" "SomeClass.java" 123
    {:java true :class "some.space.SomeClass" :method "someMethod"
     :file "SomeClass.java" :line 123}]

   ["some.space.SomeClass$SomeInner" "someMethod" "SomeClass.java" 123
    {:java true :class "some.space.SomeClass$SomeInner" :method "someMethod"
     :file "SomeClass.java" :line 123}]

   ["some.space.SomeClass" "someMethod" nil -1
    {:java true :class "some.space.SomeClass" :method "someMethod"
     :file nil :line nil}]])

(deftest test-parse-trace-elem
  (doseq [[class method file line parsed] cases
          :let [elem (StackTraceElement. class method file line)]]
    (is (= parsed (parse-trace-elem elem)))))

(deftest test-trim-redundant
  (let [trim-fn (resolve 'clj-stacktrace.core/trim-redundant)]
    (is (= '(d c) (trim-fn '(d c b a) '(f e b a))))
    (is (= '(c)   (trim-fn '(c b a)   '(f e b a))))
    (is (= '(d c) (trim-fn '(d c b a) '(e b a))))))

(deftest test-parse-exception
  (try
    (eval '(/))
    (catch Exception e
      (is (parse-exception e)))))
