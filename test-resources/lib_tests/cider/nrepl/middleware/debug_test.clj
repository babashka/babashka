(ns ^:debugger cider.nrepl.middleware.debug-test
  (:require
   [cider.nrepl.middleware.util.instrument :as ins]
   [cider.nrepl.middleware.util.nrepl]
   [cider.nrepl.middleware.debug  :as d]
   #_[cider.nrepl.middleware.enlighten :as e] ;; BB-TEST-PATCH babashka does not bundle enlighten
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.transport :as t]))

(def ^:const bfkey :cider.nrepl.middleware.util.instrument/breakfunction)

;; dummy STATE__
(defn STATE []
  {:msg {:original-ns "user"}
   :session-id (atom nil)
   :skip (atom false)})

(deftest irrelevant-return-value-test
  (are [x] (let [exp (clojure.walk/macroexpand-all x)]
             (= exp (clojure.walk/macroexpand-all `(d/breakpoint-if-interesting ~exp [] nil))))
    '(defn name "" [] (inc 2))
    '(defn- name "" [] (inc 2))
    '(def name "")
    '(fn name [] (inc 2))
    '(fn* name ([] (inc 2)))))

(deftest coord<-test
  (are [a b] (and (ins/coord< a b)
                  (not (ins/coord< b a)))
    [1] []
    [0] [1]
    [1] [2]
    [1 2] [1 3]
    [1 0] [1]))

(deftest skip-breaks-test
  (binding [d/*skip-breaks* (atom {:mode :all})]
    (is (#'d/skip-breaks? [] nil))
    (is (#'d/skip-breaks? nil nil))
    (is (#'d/skip-breaks? [1 2] nil))
    (is (#'d/skip-breaks? [2 2 1] nil))

    (#'d/skip-breaks! nil)
    (is (#'d/skip-breaks? [] nil))
    (is (#'d/skip-breaks? nil nil))
    (is (not (#'d/skip-breaks? [1 2] nil)))
    (is (not (#'d/skip-breaks? [2 2 1] nil)))

    (let [code "(foo (bar blah x))"
          state (assoc-in (STATE) [:msg :code] code)]
      (#'d/skip-breaks! :deeper [1 2] code nil)
      (is (d/skip-breaks? [] state))
      (is (not (#'d/skip-breaks? [1 2] state)))
      (is (not (#'d/skip-breaks? [2 2 1] state)))
      (is (#'d/skip-breaks? [1 2 3] state)))))

(defn- send-override
  [value]
  (fn [trans {:keys [key]}]
    (deliver (@d/promises key) (str value))))

(defn- send-override-msg
  [trans {:keys [key] :as msg}]
  (let [pro (@d/promises key)]
    (swap! d/promises dissoc key)
    (deliver pro (str msg))))

(defmacro extras
  "Add :original-ns to map m."
  [& [m]]
  `(merge {:original-ns "user"}
          ~m))

(defmacro locals []
  `~(#'d/locals-capturer &env))

(defmacro add-locals
  "Add locals to map m."
  [& [m]]
  `(assoc ~m
          :locals ~(#'d/locals-capturer &env)
          :original-ns "user"))

(deftest read-debug-input-roundtrip
  (reset! d/promises {})
  (with-redefs [t/send send-override-msg]
    (is (:test (#'d/read-debug-input (extras {:test true}) :test-type "prompt")))
    (is (empty? @d/promises))
    (are [pred key] (pred (key (#'d/read-debug-input (extras {:test true}) :test-type "prompt")))
      true? :test
      :need-debug-input :status
      string? :key
      #(= :test-type %) :input-type)))

(deftest read-debug-command
  (reset! @#'d/debugger-message {})

  ;; Check functionality
  (with-redefs [d/abort! (constantly :aborted)
                t/send   (send-override :quit)]
    (is (= :aborted (#'d/read-debug-command nil 'value (locals) (STATE)))))
  (with-redefs [t/send (send-override :next)]
    (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE)))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :continue)]
      (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE))))
      (is (#'d/skip-breaks? nil (STATE)))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :out)]
      (is (= 'value (#'d/read-debug-command [1 2 3] 'value (locals) (STATE))))
      (is (#'d/skip-breaks? [1 2 3] (STATE)))
      (is (#'d/skip-breaks? [1 2 4] (STATE)))
      (is (not (#'d/skip-breaks? [1 2] (STATE))))))
  (with-redefs [t/send (send-override :inject)]
    (is (= :inject (#'d/read-debug-command nil 'value (locals) (STATE))))))

(deftest read-debug-command-eval-test
  (let [replies (atom [:eval 100 :next])]
    (with-redefs [t/send (fn [trans {:keys [key]}]
                           (deliver (@d/promises key) (str (first @replies)))
                           (swap! replies rest))]
      (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE)))))))

(deftest read-eval-expression-test
  (reset! d/debugger-message {})
  (let [x 1]
    (with-redefs [t/send (send-override '(inc 10))]
      (is (= 11 (#'d/read-eval-expression "" (add-locals {})))))
    (with-redefs [t/send (send-override '(inc x))]
      (is (= 2 (#'d/read-eval-expression "" (add-locals {})))))))

(deftest eval-add-locals
  (reset! @#'d/debugger-message {})
  (is (= 2
         (let [x 1]
           (#'d/eval-with-locals '(inc x) (add-locals {}))))))

(deftest initialize-test
  (with-redefs [d/debugger-message (atom nil)]
    (let [resp (atom nil)]
      (with-redefs [t/send (fn [_ response] (reset! resp response))]
        (#'d/initialize {:hi true}))
      (is (= @d/debugger-message {:hi true}))
      (is (not (:status @resp))))
    (let [resp (atom nil)]
      (with-redefs [t/send (fn [_ response] (reset! resp response))]
        (#'d/initialize {:hi true}))
      (is (:status @resp)))))

(deftest locals-for-message-test
  (let [x 1
        to__ignore 0]
    (is (= '(("x" "1"))
           (#'d/locals-for-message (locals))))))

(deftest eval-expression-with-code-test
  (is (= (#'d/read-eval-expression
          "Unused prompt" (add-locals {:some "random", 'meaningless :map}) '(inc 1))
         2))
  (let [x 10]
    (is (= (#'d/read-eval-expression
            "Unused prompt" (add-locals {:some "random", 'meaningless :map}) '(inc x))
           11))))

(deftest debug-reader-test
  (is (empty? (remove #(bfkey (meta %))
                      (d/debug-reader '[a b c]))))
  (is (bfkey (meta (d/debug-reader '[a b c]))))
  (is (= (count (remove #(bfkey (meta %))
                        (d/debug-reader '[a :b 10])))
         2)))

(deftest breakpoint-reader-test
  (is (bfkey (meta (d/breakpoint-reader '[a b c]))))
  (is (= '[a :b 10 "ok"]
         (remove #(bfkey (meta %)) (d/breakpoint-reader '[a :b 10 "ok"]))))
  ;; Just don't error
  (is (= 4 (count (mapv d/breakpoint-reader '[a :b 10 "ok"])))))

(deftest reader-macros-test
  (binding [*data-readers* {'dbg d/debug-reader}]
    ;; Reader macro variants
    (is (empty? (remove #(bfkey (meta %)) (read-string "#dbg [a b c]"))))
    (is (bfkey (meta (read-string "#dbg [a b c]"))))
    (is (= (count (remove #(bfkey (meta %)) (read-string "#dbg [a :b 10]")))
           2))))

(deftest pr-short-test
  (reset! d/print-options {:length 4, :level 2})
  (is (< (count (d/pr-short [1 2 3 4 5 6 7 8 9 10]))
         (count (pr-str [1 2 3 4 5 6 7 8 9 10]))))
  (is (< (count (d/pr-short [[[1 2 3 4]]]))
         (count (pr-str [[[1 2 3 4]]]))))
  (is (= (d/pr-short [1 2 3 4])
         (pr-str [1 2 3 4])))
  (reset! d/print-options {})
  (is (= 2003 (count (d/pr-short (range))))))

(deftest breakpoint
  ;; Map merging
  (let [capture (atom nil)]
    (with-redefs [d/read-debug-command (fn [_ v _ s]
                                         (reset! capture (assoc (:msg s) :value v))
                                         v)
                  d/debugger-message   (atom [:fake])
                  d/*skip-breaks*      (atom nil)]
      (with-bindings {#'d/*top-level-form-meta*
                      {::d/form-info {:code        :code
                                      :file        :file
                                      :original-id :id}
                       :line         :line
                       :column       :column}}
        (let [form `(d/with-initial-debug-bindings
                      (d/breakpoint-if-interesting (inc 10) {:coor [6]} ~'(inc 10)))
              m (eval form)]
          (is+ {:value       11
                :file        :file
                :line        :line
                :column      :column
                :code        :code
                :original-id :id}
               @capture))
        (reset! d/debugger-message [:fake])
        ;; Locals capturing
        (eval `(d/with-initial-debug-bindings
                 (let [~'x 10]
                   (d/breakpoint-if-interesting
                    (locals) {:coor [1]} nil))))
        (is (= (:value @capture) '{x 10}))
        ;; Top-level sexps are not debugged, just returned.
        (is (= (eval `(d/with-initial-debug-bindings
                        (let [~'x 10]
                          (d/breakpoint-if-interesting
                           (locals)
                           {:coor []}
                           nil))))
               '{x 10}))))))

(deftest abort-without-session-thread-test
  ;; #1018: quitting a debug session (`q`) calls `abort!`, which stops the eval
  ;; thread read from the session metadata. In some setups that metadata carries
  ;; no `:thread`, and `abort!` used to NPE trying to `.stop` nil. It must quit
  ;; cleanly (reply with QUIT) instead.
  (let [sent (atom [])]
    (with-redefs [t/send (fn [_ msg] (swap! sent conj msg))]
      (binding [*msg* {:transport :fake
                       :id "id"
                       ;; a session atom with no :thread in its metadata
                       :session (atom {})}]
        (is (nil? (#'d/abort!)) "returns without throwing")
        (is (= 'QUIT (:value (first @sent))) "replies with QUIT")))))

(defn- read-with-debug-readers
  "Read `code` with cider's debug/enlighten reader macros active, the way the
  eval op does before handing the form to `instrument-and-eval`."
  [code]
  (binding [*data-readers* (merge *data-readers*
                                  {'dbg   #'d/debug-reader
                                   'break #'d/breakpoint-reader
                                   #_#_'light #'e/light-reader})] ;; BB-TEST-PATCH
    (read-string {:read-cond :allow} code)))

(deftest dbg-on-bare-collection-literal-test
  ;; #1016: `#dbg` on a bare collection literal that closes over a local (or a
  ;; qualified, non-core name) used to throw "Unable to resolve symbol: STATE__".
  ;; Only the collection's interior received a breakpoint, while the wrapper that
  ;; binds the STATE__ anaphor - applied to the tagged top-level form - was
  ;; skipped for collection literals (which aren't "interesting" on their own),
  ;; so the interior breakpoint referenced an unbound STATE__.
  (testing "instrumenting and compiling no longer throws"
    (are [code] (var? (d/instrument-and-eval (read-with-debug-readers code)))
      "(defn i1016-vec [x] #dbg [x])"
      "(defn i1016-nested [x] #dbg [(inc x)])"
      "(defn i1016-map [x] #dbg {:k x})"
      "(defn i1016-qualified [x] #dbg [clojure.string/upper-case])"))
  (testing "the instrumented fn runs, with a fresh (per-call) debug session"
    (let [v (d/instrument-and-eval (read-with-debug-readers "(defn i1016-run [x] #dbg [x])"))]
      (binding [d/*skip-breaks* (atom {:mode :all})]
        (is (= [5] (v 5)))
        (is (= [6] (v 6)))))))

#_ ;; BB-TEST-PATCH babashka does not bundle enlighten
(deftest light-reader-binds-state-test
  ;; #1017: the `#light` reader routes through `instrument-and-eval` (rather than
  ;; the `:enlighten` flag path), which didn't establish the STATE__ bindings, so
  ;; `light-form` - which references STATE__ directly - threw "Unable to resolve
  ;; symbol: STATE__".
  (testing "instrumenting and compiling no longer throws"
    (are [code] (var? (d/instrument-and-eval (read-with-debug-readers code)))
      "#light (defn l1017-simple [x] (+ x 1))"
      "#light (defn l1017-vec [x] [x])"))
  (testing "the enlightened fn runs and computes correctly"
    (with-redefs [d/debugger-send (fn [& _] nil)]
      (let [v (d/instrument-and-eval (read-with-debug-readers "#light (defn l1017-run [x] (+ x 1))"))]
        (is (= 42 (v 41)))))))

(deftest instrument-defrecord-with-methods-test
  ;; A `defrecord`/`deftype` with inline protocol methods used to throw "Unable
  ;; to resolve symbol: STATE__" under instrumentation, because the method bodies
  ;; compile to real class methods that can't close over the STATE__ local. The
  ;; bodies are now left uninstrumented, so the form compiles and its methods run.
  (binding [d/*skip-breaks* (atom {:mode :all})]
    (is (some? (d/instrument-and-eval
                (read-with-debug-readers
                 "#dbg (defrecord DbgRec [x] clojure.lang.IDeref (deref [_] (inc x)))")))
        "compiles without an unresolved STATE__")
    (is (= 6 (eval '(deref (->DbgRec 5))))
        "the record's instrumented method still works")))

(deftest method-too-large?-test
  (are [msg] (#'d/method-too-large? msg)
    (RuntimeException. "blah Method code too large! blah")
    (RuntimeException. "wrapper" (RuntimeException. "Method code too large!")))
  (are [msg] (not (#'d/method-too-large? msg))
    (RuntimeException. "some other error")
    (RuntimeException.)))

(deftest large-instrumented-form-retries-without-locals-test
  ;; #750: a form too large to instrument WITH locals is retried WITHOUT them
  ;; (which produces smaller code), with a warning; breakpoints still work, just
  ;; without local inspection, and the result is returned. Forced here by making
  ;; the instrumented form throw the method-size error only while local capture
  ;; is on, so the first attempt fails and the retry succeeds - avoids compiling
  ;; a real ~64KB-method form, which needlessly bloats the test JVM.
  (let [notes (atom [])]
    (with-redefs [ins/instrument-tagged-code
                  (fn [_] '(if cider.nrepl.middleware.debug/*do-locals*
                             (throw (java.lang.RuntimeException. "Method code too large!"))
                             :retried-without-locals))
                  cider.nrepl.middleware.util.nrepl/notify-client
                  (fn [_ _ type] (swap! notes conj type))]
      (binding [*msg* {:transport :fake}]
        (is (= :retried-without-locals (d/instrument-and-eval '(def i750-unused)))
            "retried without locals and returned the result")
        (is (= [:warning] @notes)
            "warned once and did not escalate to an error")))))

(deftest too-large-to-instrument-reports-error-test
  ;; #750: when a form is too large to instrument even without locals, report a
  ;; clear error and leave it unevaluated - rather than a raw compiler stacktrace
  ;; or a silent uninstrumented eval that looks like debugging is on. Forced here
  ;; by making instrumentation produce a form that throws the method-size error,
  ;; so both the with-locals attempt and the without-locals retry fail.
  (let [notes (atom [])]
    (with-redefs [ins/instrument-tagged-code
                  (fn [_] '(throw (java.lang.RuntimeException. "Method code too large!")))
                  cider.nrepl.middleware.util.nrepl/notify-client
                  (fn [_ _ type] (swap! notes conj type))]
      (binding [*msg* {:transport :fake}]
        (is (nil? (d/instrument-and-eval '(def i750-unreachable :defined)))
            "returns nil without evaluating the form")
        (is (nil? (resolve 'i750-unreachable))
            "the form was not evaluated")
        (is (= :error (last @notes))
            "the final notification is a clear error")))))

#_ ;; BB-TEST-PATCH the JVM method size limit does not apply, and sci cannot read Compiler$CompilerException
(deftest instrumentation-stress-test
  (testing "able to compile this function full of locals"
    (is
     (-> '(defn a-fn [a0]
            (let [a0 (long (+))
                  a1 (long (+ a0))
                  a2 (+ a0 a1)
                  a3 (+ a0 a1 a2)
                  a4 (+ a0 a1 a2 a3)
                  a5 (+ a0 a1 a2 a3 a4)
                  a6 (+ a0 a1 a2 a3 a4 a5)
                  a7 (+ a0 a1 a2 a3 a4 a5 a6)
                  a8 (+ a0 a1 a2 a3 a4 a5 a6 a7)
                  a9 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8)
                  a10 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9)
                  a11 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)
                  a12 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11)
                  a13 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12)
                  a14 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13)
                  a15 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14)
                  a16 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15)
                  a17 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16)
                  a18 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17)
                  a19 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18)
                  a20 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19)
                  a21 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20)
                  a22 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21)
                  a23 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22)
                  a24 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23)
                  a25 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24)
                  a26 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25)]
              a0))
         d/debug-reader
         ins/instrument-tagged-code
         eval)))

  (testing "fails if there is one extra line"
    (is (thrown? clojure.lang.Compiler$CompilerException
                 (-> '(defn a-fn [a0]
                        (let [a0 (long (+))
                              a1 (long (+ a0))
                              a2 (+ a0 a1)
                              a3 (+ a0 a1 a2)
                              a4 (+ a0 a1 a2 a3)
                              a5 (+ a0 a1 a2 a3 a4)
                              a6 (+ a0 a1 a2 a3 a4 a5)
                              a7 (+ a0 a1 a2 a3 a4 a5 a6)
                              a8 (+ a0 a1 a2 a3 a4 a5 a6 a7)
                              a9 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8)
                              a10 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9)
                              a11 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)
                              a12 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11)
                              a13 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12)
                              a14 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13)
                              a15 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14)
                              a16 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15)
                              a17 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16)
                              a18 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17)
                              a19 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18)
                              a20 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19)
                              a21 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20)
                              a22 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21)
                              a23 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22)
                              a24 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23)
                              a25 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24)
                              a26 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25)
                              a27 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25 a26)
                              a28 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25 a26 a27)]
                          a0))
                     d/debug-reader
                     ins/instrument-tagged-code
                     eval)))))
