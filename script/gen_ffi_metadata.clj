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
;; - downcalls: none. Every fixed shape goes through a generated trampoline,
;;   and a variadic call goes through libffi, so a native image needs no FFM
;;   downcall descriptor at all
;; - upcalls: a longs, b doubles with a+b <= 4 and b <= 2, returns
;;   void/long/double; pure-integer upcalls additionally up to arity 6
;;   (FSEvents passes six, GLFW's key callback five)
;; - reflection: clojure.lang.IFn.invoke arities 0..6, for upcall
;;   method handles (callbacks take at most 6 arguments)
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
;; A trampoline calls with the fixed convention, which is the wrong one for a
;; variadic function on Apple arm64, so variadic calls get no trampoline and
;; go through libffi.
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

;; the one signature family: up to 6 args with at most 3 FP args in any
;; order (or a uniform 4), pure integer signatures up to 10, float returns
;; only up to 4 args. Canonical (sorted) shapes become trampolines; on
;; Windows argument sorting is unsound, so there every ORDERING of the
;; family gets its own trampoline instead - a bigger binary on Windows
;; only, full call speed everywhere.
(defn fp-ok? [args]
  (let [fp (filterv #{"jdouble" "jfloat"} args)]
    (or (<= (count fp) 3)
        (and (= 4 (count fp)) (apply = fp)))))

(def ordered-shapes
  ;; every ordering of the family; trampolines on Windows
  (concat
   (for [n (range 0 7)
         args (combos-n ["jlong" "jdouble" "jfloat"] n)
         :when (fp-ok? args)]
     (vec args))
   (map #(vec (repeat % "jlong")) (range 7 11))))

(def downcalls
  ;; A native image makes no FFM downcall: a fixed shape goes through a
  ;; generated trampoline, a variadic call and a struct call through libffi.
  ;; A build without libffi refuses those calls rather than carrying the
  ;; descriptors for them.
  [])

(def upcalls
  ;; same family both modes: <= 4 args, <= 2 doubles, no float, plus
  ;; pure-integer shapes of arity 5 and 6; Windows needs every ordering
  ;; (callbacks do not sort there either), which for a pure-integer shape
  ;; is the one ordering
  (concat
   (if windows?
     (for [args (mapcat #(combos-n ["jlong" "jdouble"] %) (range 0 5))
           :when (<= (count (filter #(= "jdouble" %) args)) 2)
           ret ["void" "jlong" "jdouble"]]
       {"returnType" ret "parameterTypes" (vec args)})
     (for [a (range 0 5)
           b (range 0 3)
           :when (<= (+ a b) 4)
           ret ["void" "jlong" "jdouble"]]
       {"returnType" ret "parameterTypes" (shape a b 0)}))
   (for [a (range 5 7)
         ret ["void" "jlong" "jdouble"]]
     {"returnType" ret "parameterTypes" (shape a 0 0)})))

(def reflection
  ;; callbacks call the wrapped IFn through a bound MethodHandle; they take
  ;; at most 6 arguments
  [{"type" "clojure.lang.IFn"
    "methods" (for [n (range 0 7)]
                {"name" "invoke"
                 "parameterTypes" (vec (repeat n "java.lang.Object"))})}])

(println "downcalls:" (count downcalls) "upcalls:" (count upcalls))

(spit "resources/META-INF/native-image/babashka/ffi/reachability-metadata.json"
      (json/generate-string {"foreign" {"downcalls" downcalls
                                        "upcalls" upcalls}
                             "reflection" reflection}
                            {:pretty true}))

;; -- trampolines --------------------------------------------------------------

(def MAX-ARITY 6)

(def fp-seqs
  ;; float and double share one FP register sequence, so their relative order
  ;; is part of the shape: enumerate sequences, not counts. Mixed sequences up
  ;; to three, plus the uniform four - rlRotatef takes four floats, geometry
  ;; and matrix calls take four doubles. Measured against ~350 bindings from
  ;; the babashka demos, b12n-raylib-clj, libpython-clj and the libffi API,
  ;; the widest real signature needs three.
  (concat (mapcat #(combos-n ["jdouble" "jfloat"] %) (range 0 4))
          [(vec (repeat 4 "jfloat")) (vec (repeat 4 "jdouble"))]))

(def sorted-shapes
  ;; integer carriers first, then the FP sequence (non-Windows trampolines)
  (concat
   (for [a (range 0 (inc MAX-ARITY))
         fp fp-seqs
         :when (<= (+ a (count fp)) MAX-ARITY)]
     (vec (concat (repeat a "jlong") fp)))
   ;; a signature of only pointers and integers has one carrier class, so the
   ;; sort is the identity and any arity stays sound: EVP_PBE_scrypt takes ten
   (for [a (range (inc MAX-ARITY) 11)]
     (shape a 0 0))))

(def jchar {"jlong" "J" "jdouble" "D" "jfloat" "F" "void" "V"})
(def jtype {"J" "long" "D" "double" "F" "float" "V" "void"})
(def clj-cast {"J" "long" "D" "double" "F" "float"})

(def shape-sigs
  ;; [ret-char arg-chars-string]; Windows uses the ordered family
  (for [args (if windows? ordered-shapes sorted-shapes)
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
           ;; a Java method tops out at 64KB of bytecode; the Windows ordered
           ;; family exceeds one switch, so dispatch in chunks
           (let [chunks (partition-all 300 (map-indexed vector shape-sigs))]
             (str
              (str/join
               "\n"
               (map-indexed
                (fn [ci chunk]
                  (str "\n    private static Object dispatch" ci
                       "(int id, long fn, Object[] a) {\n"
                       "        switch (id) {\n"
                       (str/join "\n" (map (fn [[id sig]] (java-case id sig)) chunk))
                       "\n        }\n"
                       "        throw new IllegalArgumentException(\"bad shape id: \" + id);\n"
                       "    }\n"))
                chunks))
              "\n    public static Object dispatch(int id, long fn, Object[] a) {\n"
              "        switch (id / 300) {\n"
              (str/join "\n"
                        (map-indexed
                         (fn [ci _]
                           (str "        case " ci ": return dispatch" ci "(id, fn, a);"))
                         chunks))
              "\n        }\n"
              "        throw new IllegalArgumentException(\"bad shape id: \" + id);\n"
              "    }\n"))
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
