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
;; - upcalls: a longs, b doubles with a+b <= 4 and b <= 2, returns
;;   void/long/double
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
;;
;; Also generates the native-image call trampolines for the sorted shape set
;; (identical in both modes):
;; - src-java/babashka/impl/FfiTrampoline.java: per shape, a CFunctionPointer
;;   interface and a static method calling through a raw function pointer via
;;   @InvokeCFunctionPointer - a COMPILED direct call (~2ns), where FFM
;;   downcall handles are interpreted in a native image (~3.4us).
;; - src/babashka/impl/ffi_trampolines.clj: shape key -> builder fn map used
;;   by babashka.ffi's cfn on the native image.
;; Variadic calls are excluded (a fixed-convention pointer call has the same
;; Apple arm64 variadic trap) and stay on FFM.
(require '[cheshire.core :as json]
         '[clojure.string :as str])

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
     (for [a (range 7 11)]
       (shape a 0 0)))))

(def downcalls
  ;; Non-variadic downcalls only need FFM descriptors on Windows: elsewhere
  ;; every non-variadic shape goes through a generated trampoline (identical
  ;; coverage by construction), so registering the descriptors too would only
  ;; add dead stubs. Variadic calls always use FFM.
  (concat
   (when windows?
     (for [args downcall-shapes
           ret (cond-> ["void" "jlong" "jdouble"]
                 (<= (count args) 4) (conj "jfloat"))]
       {"returnType" ret "parameterTypes" args}))
   ;; boundary == n covers the empty-tail call of a variadic function
   (for [n (range 1 6)
         args (combos-n ["jlong" "jdouble"] n)
         :when (<= (count (filter #(= "jdouble" %) args)) 2)
         boundary (range 1 (inc (min 3 n)))
         ret ["void" "jlong"]]
     {"returnType" ret "parameterTypes" (vec args)
      "options" {"firstVariadicArg" boundary}})))

(def upcalls
  (if windows?
    (for [args (mapcat #(combos-n ["jlong" "jdouble"] %) (range 0 5))
          ret ["void" "jlong" "jdouble"]]
      {"returnType" ret "parameterTypes" (vec args)})
    (for [a (range 0 5)
          b (range 0 3)
          :when (<= (+ a b) 4)
          ret ["void" "jlong" "jdouble"]]
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

;; -- trampolines --------------------------------------------------------------

(def fp-seqs
  ;; float and double share one FP register sequence, so their relative
  ;; order is part of the shape: enumerate sequences, not counts. Mixed
  ;; sequences up to 4, pure-double up to 6.
  (concat (mapcat #(combos-n ["jdouble" "jfloat"] %) (range 0 5))
          [(vec (repeat 5 "jdouble")) (vec (repeat 6 "jdouble"))]))

(def sorted-shapes
  ;; integer carriers first, then the FP sequence; regardless of windows mode
  (concat
   (for [a (range 0 7)
         fp fp-seqs
         :when (<= (+ a (count fp)) 7)]
     (vec (concat (repeat a "jlong") fp)))
   (for [a (range 7 11)]
     (shape a 0 0))))

(def jchar {"jlong" "J" "jdouble" "D" "jfloat" "F" "void" "V"})
(def jtype {"J" "long" "D" "double" "F" "float" "V" "void"})
(def clj-cast {"J" "long" "D" "double" "F" "float"})

(def shape-sigs
  ;; [ret-char arg-chars-string], mirrors the registered descriptor set
  (for [args sorted-shapes
        ret (cond-> ["void" "jlong" "jdouble"]
              (<= (count args) 4) (conj "jfloat"))]
    [(jchar ret) (str/join (map jchar args))]))

(defn java-iface [[ret args]]
  (let [nm (str ret "_" args)
        params (map-indexed (fn [i c] (str (jtype (str c)) " a" i)) args)]
    (str "    public interface F_" nm " extends CFunctionPointer {\n"
         "        @InvokeCFunctionPointer\n"
         "        " (jtype ret) " invoke(" (str/join ", " params) ");\n"
         "    }\n")))

(defn java-case [id [ret args]]
  (let [nm (str ret "_" args)
        unbox {"J" "longV" "D" "dblV" "F" "fltV"}
        call-args (map-indexed (fn [i c] (str (unbox (str c)) "(a[" i "])")) args)
        call (str "((F_" nm ") WordFactory.pointer(fn)).invoke(" (str/join ", " call-args) ")")]
    (str "        case " id ": "
         (if (= "V" ret) (str call "; return null;") (str "return " call ";")))))

;; One switch-dispatch method instead of a static method per shape: a shape's
;; per-method class metadata and the per-shape Clojure closures were most of
;; the trampolines' image size.
(spit "src-java/babashka/impl/FfiTrampoline.java"
      (str "// Generated by script/gen_ffi_metadata.clj. Do not edit.\n"
           "package babashka.impl;\n\n"
           "import org.graalvm.nativeimage.c.function.CFunctionPointer;\n"
           "import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;\n"
           "import org.graalvm.word.WordFactory;\n\n"
           "/** Native-image call trampolines for babashka.ffi: one compiled\n"
           " * direct call per canonical shape, dispatched by shape id. Only\n"
           " * functional in a native image; never invoked on the JVM. */\n"
           "public final class FfiTrampoline {\n"
           "    private FfiTrampoline() {}\n\n"
           "    private static long longV(Object o) { return ((Number) o).longValue(); }\n"
           "    private static double dblV(Object o) { return ((Number) o).doubleValue(); }\n"
           "    private static float fltV(Object o) { return ((Number) o).floatValue(); }\n\n"
           (str/join "\n" (map java-iface shape-sigs))
           "\n    public static Object dispatch(int id, long fn, Object[] a) {\n"
           "        switch (id) {\n"
           (str/join "\n" (map-indexed java-case shape-sigs))
           "\n        }\n"
           "        throw new IllegalArgumentException(\"bad shape id: \" + id);\n"
           "    }\n"
           "}\n"))

(spit "src/babashka/impl/ffi_trampolines.clj"
      (str ";; Generated by script/gen_ffi_metadata.clj. Do not edit.\n"
           "(ns babashka.impl.ffi-trampolines\n"
           "  {:no-doc true}\n"
           "  (:import [babashka.impl FfiTrampoline]))\n\n"
           "(def ids\n"
           "  {"
           (str/join "\n   "
                     (map-indexed (fn [id [ret args]]
                                    (str "\"" ret "_" args "\" " id))
                                  shape-sigs))
           "})\n\n"
           "(defn invoker [id fnp]\n"
           "  (let [id (int id)\n"
           "        fnp (long fnp)]\n"
           "    (fn [^objects a]\n"
           "      (FfiTrampoline/dispatch id fnp a))))\n"))

(println "trampolines:" (count shape-sigs))
