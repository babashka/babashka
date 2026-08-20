;; Generates the native-image reachability metadata for babashka.ffi:
;; resources/META-INF/native-image/babashka/ffi/reachability-metadata.json
;;
;; Run with: bb script/gen_ffi_metadata.clj
;;
;; babashka.ffi canonicalizes every signature before building its
;; FunctionDescriptor (integer carriers widened to long, arguments stably
;; sorted long < double < float, values permuted at call time), so only
;; COUNT-shaped descriptors need registration, not orderings. See the ABI
;; note in babashka.ffi. Shapes:
;;
;; - downcalls: a longs, b doubles, c floats with a <= 6 (x86-64 has 6
;;   integer argument registers), a+b+c <= 7, and b+c <= 4 when c > 0;
;;   returns void/long/double, plus float for shapes of <= 4 args
;; - pure-integer downcalls additionally up to arity 10 (EVP_PBE_scrypt
;;   takes 10): with a single carrier class the sort is the identity, so
;;   stack-passed arguments keep their declared order and any arity is sound
;; - variadic downcalls (order preserved, not sorted): arity 2..5 over
;;   {long,double} with <= 2 doubles, boundary 1..3, returns void/long
;; - upcalls: a longs, b doubles with a+b <= 4 and b <= 2, returns void/long
;; - reflection: clojure.lang.IFn.invoke arities 0..8, for upcall
;;   method handles
;;
;; Type names are JNI ("jlong", "jdouble", "jfloat"): fixed sizes on every
;; platform. C "long" is 32-bit on Windows and must not be used here.
;;
;; With "windows" as the first argument, emits ORDERED shapes instead of
;; count shapes: the Win64 ABI assigns argument registers by position, so
;; babashka.ffi does not sort there (sort-permutation returns nil) and every
;; ordering needs its own descriptor. The Windows build must run this mode
;; before script/uberjar. Untested on Windows.
(require '[cheshire.core :as json])

(def windows? (= "windows" (first *command-line-args*)))

(defn combos-n [types n]
  (if (zero? n)
    [[]]
    (reduce (fn [acc _] (for [c acc t types] (conj c t)))
            [[]]
            (range n))))

(defn shape [a b c]
  (vec (concat (repeat a "jlong") (repeat b "jdouble") (repeat c "jfloat"))))

(def downcall-shapes
  (if windows?
    (distinct
     (concat (combos-n ["jlong" "jdouble"] 0)
             (mapcat #(combos-n ["jlong" "jdouble"] %) (range 1 7))
             (mapcat #(combos-n ["jlong" "jdouble" "jfloat"] %) (range 1 5))
             (map #(vec (repeat % "jlong")) (range 7 11))))
    (concat
     (for [a (range 0 7)
           b (range 0 7)
           c (range 0 5)
           :when (and (<= (+ a b c) 7)
                      (or (zero? c) (<= (+ b c) 4)))]
       (shape a b c))
     (for [a (range 8 11)]
       (shape a 0 0)))))

(def downcalls
  (concat
   (for [args downcall-shapes
         ret (cond-> ["void" "jlong" "jdouble"]
               (<= (count args) 4) (conj "jfloat"))]
     {"returnType" ret "parameterTypes" args})
   (for [n (range 2 6)
         args (combos-n ["jlong" "jdouble"] n)
         :when (<= (count (filter #(= "jdouble" %) args)) 2)
         boundary (range 1 (inc (min 3 (dec n))))
         ret ["void" "jlong"]]
     {"returnType" ret "parameterTypes" (vec args)
      "options" {"firstVariadicArg" boundary}})))

(def upcalls
  (if windows?
    (for [args (mapcat #(combos-n ["jlong" "jdouble"] %) (range 0 5))
          ret ["void" "jlong"]]
      {"returnType" ret "parameterTypes" (vec args)})
    (for [a (range 0 5)
          b (range 0 3)
          :when (<= (+ a b) 4)
          ret ["void" "jlong"]]
      {"returnType" ret "parameterTypes" (shape a b 0)})))

(def reflection
  [{"type" "clojure.lang.IFn"
    "methods" (for [n (range 0 9)]
                {"name" "invoke"
                 "parameterTypes" (vec (repeat n "java.lang.Object"))})}])

(println "downcalls:" (count downcalls) "upcalls:" (count upcalls))

(spit "resources/META-INF/native-image/babashka/ffi/reachability-metadata.json"
      (json/generate-string {"foreign" {"downcalls" downcalls
                                        "upcalls" upcalls}
                             "reflection" reflection}
                            {:pretty true}))
