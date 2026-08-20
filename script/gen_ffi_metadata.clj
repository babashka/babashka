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
;;   integer argument registers), a+b+c <= 8, and b+c <= 4 when c > 0;
;;   returns void/long/double, plus float for shapes of <= 4 args
;; - variadic downcalls (order preserved, not sorted): arity 2..6 over
;;   {long,double} with <= 2 doubles, boundary 1..3, returns void/long
;; - upcalls: a longs, b doubles with a+b <= 4, returns void/long/double
;; - reflection: clojure.lang.IFn.invoke arities 0..8, for upcall
;;   method handles
(require '[cheshire.core :as json])

(defn combos-n [types n]
  (if (zero? n)
    [[]]
    (reduce (fn [acc _] (for [c acc t types] (conj c t)))
            [[]]
            (range n))))

(defn shape [a b c]
  (vec (concat (repeat a "long") (repeat b "double") (repeat c "float"))))

(def downcall-shapes
  (for [a (range 0 7)
        b (range 0 7)
        c (range 0 5)
        :when (and (<= (+ a b c) 8)
                   (or (zero? c) (<= (+ b c) 4)))]
    (shape a b c)))

(def downcalls
  (concat
   (for [args downcall-shapes
         ret (cond-> ["void" "long" "double"]
               (<= (count args) 4) (conj "float"))]
     {"returnType" ret "parameterTypes" args})
   (for [n (range 2 7)
         args (combos-n ["long" "double"] n)
         :when (<= (count (filter #(= "double" %) args)) 2)
         boundary (range 1 (inc (min 3 (dec n))))
         ret ["void" "long"]]
     {"returnType" ret "parameterTypes" (vec args)
      "options" {"firstVariadicArg" boundary}})))

(def upcalls
  (for [a (range 0 5)
        b (range 0 5)
        :when (<= (+ a b) 4)
        ret ["void" "long" "double"]]
    {"returnType" ret "parameterTypes" (shape a b 0)}))

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
