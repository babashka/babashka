;; A struct that C returns by value: libc's div, which gives quotient and
;; remainder in one struct. A layout names the fields, the value is a map,
;; and the call goes through libffi. The last section measures what that
;; costs against a call that takes only primitives.
;;
;;   bb examples/ffi/structs.clj

(require '[babashka.ffi :as ffi :refer [defcfn]])

(def div-t [:struct [[:quot :int] [:rem :int]]])

(defcfn c-div "div" [:int :int] div-t)
(defcfn c-abs "abs" [:int] :int)

(println "div(7, 2) =" (c-div 7 2))
(println "sizeof div_t =" (ffi/sizeof div-t) "bytes, aligned to" (ffi/alignof div-t))

;; -- Measure: a struct call through libffi, a primitive call through a
;; -- trampoline. ---------------------------------------------------------------

(def N 200000)

(defn bench [label f]
  (dotimes [_ 20000] (f))
  (let [t0 (System/nanoTime)]
    (dotimes [_ N] (f))
    (println (format "  %-28s %5d ns/call" label (quot (- (System/nanoTime) t0) N)))))

(bench "div, struct by value" #(c-div 7 2))
(bench "abs, primitives only" #(c-abs -7))

(println (if (= {:quot 3 :rem 1} (c-div 7 2)) "STRUCTS OK" "STRUCTS FAIL"))
