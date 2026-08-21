(ns babashka.ffi-test
  (:require
   [babashka.process :as p]
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [expr]
  (edn/read-string (tu/bb "-e" (pr-str expr))))

(def ffi-require
  ;; explicit msvcrt on Windows: the default lookup does not cover the CRT
  ;; there, and ucrtbase hides the printf family behind header inlines
  (if tu/windows?
    '(do (require '[babashka.ffi :as ffi :refer [defcfn]])
         (ffi/load-system-library "msvcrt"))
    '(require '[babashka.ffi :as ffi :refer [defcfn]])))

(def skip?
  ;; the static binary has no dlopen and no FFM default lookup
  (= "true" (System/getenv "BABASHKA_STATIC")))

(def home-var (if tu/windows? "PATH" "HOME"))
(def snprintf-sym (if tu/windows? "_snprintf" "snprintf"))

(def test-lib
  "Path of the compiled test C library, nil when it cannot be built.
  Compiled on demand with cc; skipped on Windows (no compiler on PATH in CI)."
  (delay
    (when-not (or skip? tu/windows?)
      (let [out (io/file "target" "ffi-test-lib"
                         (if (= "Mac OS X" (System/getProperty "os.name"))
                           "libffitest.dylib" "libffitest.so"))
            src (io/file "test-resources" "ffi_test_lib.c")]
        (io/make-parents out)
        (when (or (.exists out)
                  (try (zero? (:exit (p/sh "cc" "-shared" "-fPIC" "-O1"
                                           "-o" (str out) (str src))))
                       (catch Exception _ false)))
          (.getAbsolutePath out))))))

(defn lib-require [path]
  `(do (require '[babashka.ffi :as ~'ffi :refer [~'defcfn]])
       (ffi/load-library ~path)))

(deftest downcall-test
  (when-not skip?
    (testing "int widening and narrowing"
      (is (= 42 (bb `(do ~ffi-require
                         ((ffi/cfn "abs" [:int] :int) -42))))))
    (testing "string argument, long return"
      (is (= 14 (bb `(do ~ffi-require
                         ((ffi/cfn "strlen" [:string] :size_t) "hello babashka"))))))
    (testing "string return"
      (is (= (System/getenv home-var)
             (bb `(do ~ffi-require
                      ((ffi/cfn "getenv" [:string] :string) ~home-var))))))
    (testing "exact float"
      (is (= 3.0 (bb `(do ~ffi-require
                          ((ffi/cfn "sqrtf" [:float] :float) 9.0))))))
    (testing "doubles"
      (is (= 1024.0 (bb `(do ~ffi-require
                             ((ffi/cfn "pow" [:double :double] :double) 2 10))))))
    (testing "argument reordering: interleaved double and int"
      (is (= 12.0 (bb `(do ~ffi-require
                           ((ffi/cfn "ldexp" [:double :int] :double) 1.5 3))))))))

(deftest defcfn-test
  (when-not skip?
    (testing "name, C symbol, argtypes, return type"
      (is (= 5 (bb `(do ~ffi-require
                        (~'defcfn ~'strlen "strlen" [:string] :size_t)
                        (~'strlen "hello"))))))
    (testing "optional docstring and attribute map"
      (is (= [5 "Length of a C string." true "1.0"]
             (bb `(do ~ffi-require
                      (~'defcfn ~'strlen
                       "Length of a C string." {:private true :added "1.0"}
                       "strlen" [:string] :size_t)
                      (let [m# (meta (resolve '~'strlen))]
                        [(~'strlen "hello") (:doc m#) (:private m#) (:added m#)]))))))
    (testing "too many forms before the C symbol"
      (is (thrown? Exception
                   (bb `(do ~ffi-require
                            (~'defcfn ~'bad "a" "b" "strlen" [:string] :size_t))))))))

(deftest memory-test
  (when-not skip?
    (testing "alloc, typed write and read, free"
      (is (= [-7 1.5 255]
             (bb `(do ~ffi-require
                      (let [p (ffi/alloc 16)]
                        (ffi/write p :int 0 -7)
                        (ffi/write p :double 8 1.5)
                        (ffi/write p :uint8 4 255)
                        (let [res [(ffi/read p :int) (ffi/read p :double 8) (ffi/read p :uint8 4)]]
                          (ffi/free p)
                          res)))))))
    (testing "string round trip through foreign memory"
      (is (= "abc" (bb `(do ~ffi-require
                            (let [p (ffi/string->ptr "abc")
                                  s (ffi/ptr->string p)]
                              (ffi/free p)
                              s))))))
    (testing "out parameter"
      (is (= "xy" (bb `(do ~ffi-require
                           (let [src (ffi/string->ptr "xy")
                                 dst (ffi/alloc 3)]
                             ((ffi/cfn "memcpy" [:pointer :pointer :size_t] :pointer) dst src 3)
                             (let [s (ffi/ptr->string dst)]
                               (ffi/free src)
                               (ffi/free dst)
                               s)))))))))

(deftest varargs-test
  (when-not skip?
    (testing "snprintf through the variadic calling convention, tail inferred"
      (is (= ["x-42" "pi=4"]
             (bb `(do ~ffi-require
                      (let [f# (ffi/cfn ~snprintf-sym [:pointer :size_t :string :&] :int)
                            buf# (ffi/alloc 64)
                            r1# (do (f# buf# 64 "%s-%ld" "x" 42) (ffi/ptr->string buf#))
                            r2# (do (f# buf# 64 "pi=%.0f" 3.7) (ffi/ptr->string buf#))]
                        (ffi/free buf#)
                        [r1# r2#]))))))
    (testing "empty tail from the same binding"
      (is (= "plain" (bb `(do ~ffi-require
                              (let [f# (ffi/cfn ~snprintf-sym [:pointer :size_t :string :&] :int)
                                    buf# (ffi/alloc 64)]
                                (f# buf# 64 "plain")
                                (let [s# (ffi/ptr->string buf#)]
                                  (ffi/free buf#)
                                  s#)))))))
    (testing "marker validation"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:string :varargs :long] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:& :string] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:&] :int))))))))

(deftest callback-test
  (when-not skip?
    (testing "qsort comparator upcall"
      (is (= [1 2 3 4 5]
             (bb `(do ~ffi-require
                      (let [arr (ffi/alloc 20)]
                        (doseq [[i# v#] (map-indexed vector [5 3 1 4 2])]
                          (ffi/write arr :int (* i# 4) v#))
                        (let [cmp (ffi/callback
                                   (fn [pa# pb#]
                                     (compare (ffi/read pa# :int) (ffi/read pb# :int)))
                                   [:pointer :pointer] :int)]
                          ((ffi/cfn "qsort" [:pointer :size_t :size_t :pointer] :void)
                           arr 5 4 cmp)
                          (let [res (mapv #(ffi/read arr :int (* % 4)) (range 5))]
                            (ffi/free arr)
                            res))))))))))

(deftest load-library-test
  (when-not skip?
    (when-not tu/windows?
      (testing "load-system-library and symbol resolution in it"
        (is (string? (bb `(do ~ffi-require
                              (ffi/load-system-library "z")
                              ((ffi/cfn "zlibVersion" [] :string))))))))
    (when-not tu/windows?
    (testing "candidate vectors in the OS map, first that loads wins"
      (is (string? (bb `(do (require '[babashka.ffi :as ~'ffi])
                            (ffi/load-library
                             {:mac ["nonexistent-bb-zzz.dylib" "libz.dylib"]
                              :linux ["nonexistent-bb-zzz.so" "libz.so.1" "libz.so"]})
                            ((ffi/cfn "zlibVersion" [] :string))))))))
  (testing "find-symbol probes without binding"
    (is (= [true nil]
           (bb `(do (require '[babashka.ffi :as ~'ffi])
                    [(number? (ffi/find-symbol "strlen"))
                     (ffi/find-symbol "bb_no_such_symbol_zzz")])))))
  (testing "missing library throws"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/load-library "libdoesnotexist-bb.so"))))))
    (testing "missing symbol throws"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      ((ffi/cfn "bb_no_such_symbol" [] :void)))))))))

(deftest documented-limits-test
  ;; the limits in doc/ffi.md are a contract: check both sides of each rule
  (when (and (not skip?) tu/native?)
    (let [bind (fn [args ret]
                 (bb `(do (require '[babashka.ffi :as ~'ffi])
                          (try (ffi/cfn "abs" ~args ~ret) :ok
                               (catch Exception _# :refused)))))]
      (testing "signatures the docs say fit"
        (is (= [:ok :ok :ok :ok :ok :ok]
               [(bind [:pointer :pointer :int :int :double :float] :void)
                (bind [:pointer :double :float :double] :void)
                (bind [:float :float :float :float] :void)
                (bind [:double :double :double :double] :void)
                (bind (vec (repeat 10 :long)) :long)
                (bind [:int :int :int :int] :float)])))
      (testing "signatures the docs say do not fit"
        (is (= [:refused :refused :refused :refused]
               [(bind [:pointer :pointer :int :int :int :double :float] :void)
                (bind [:double :double :double :float] :void)
                (bind (vec (repeat 11 :long)) :long)
                (bind [:int :int :int :int :int] :float)]))))))

(deftest unsupported-signature-test
  ;; native image only: the JVM path has no signature limits
  (when (and (not skip?) tu/native?)
    (testing "out-of-family signatures fail at bind time with the limits"
      (is (thrown-with-msg?
           Exception #"unsupported signature"
           (bb `(do (require '[babashka.ffi :as ~'ffi])
                    (ffi/cfn "printf"
                             [:double :double :double :double :float :long :long :long]
                             :double)))))
      (is (thrown-with-msg?
           Exception #"unsupported signature"
           (bb `(do (require '[babashka.ffi :as ~'ffi])
                    ((ffi/cfn "printf" [:string :&] :int) "%d %d %d %d %d" 1 2 3 4 5))))))))

(deftest error-test
  (when-not skip?
    (testing "unknown type keyword"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "abs" [:intt] :int))))))
    (testing "wrong argument count"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      ((ffi/cfn "abs" [:int] :int) 1 2))))))))

(deftest argument-order-test
  (when-let [lib @test-lib]
    (testing "class-sorting permutation is invisible: every mixed shape echoes
              its args multiplied by 10^position"
      (is (= [12.5 21.0 4321.5 1234.0 321.5 321.0]
             (bb `(do ~(lib-require lib)
                      [((ffi/cfn "mix_dj" [:double :long] :double) 2.5 1)
                       ((ffi/cfn "mix_jd" [:long :double] :double) 1 2)
                       ((ffi/cfn "mix_djdj" [:double :long :double :long] :double) 1.5 2 3 4)
                       ((ffi/cfn "mix_jdjd" [:long :double :long :double] :double) 4 3 2 1)
                       ((ffi/cfn "mix_fjf" [:float :long :float] :double) 1.5 2 3)
                       ((ffi/cfn "mix_jfd" [:long :float :double] :double) 1 2 3)]))))))
  (when-let [lib @test-lib]
    (testing "pure-integer arity 7 and 10"
      (is (= [7654321 987654321]
             (bb `(do ~(lib-require lib)
                      [((ffi/cfn "arity7" ~(vec (repeat 7 :long)) :long) 1 2 3 4 5 6 7)
                       ((ffi/cfn "arity10" ~(vec (repeat 10 :long)) :long)
                        1 2 3 4 5 6 7 8 9 0)])))))))

(deftest narrowing-test
  (when-let [lib @test-lib]
    (testing "return values narrow per the declared type, not the register"
      (is (= [-1 4294967295 -1 255 -2 65535 1.5]
             (bb `(do ~(lib-require lib)
                      [((ffi/cfn "ret_int_neg" [] :int))
                       ((ffi/cfn "ret_uint_max" [] :uint))
                       ((ffi/cfn "ret_int8_neg" [] :int8))
                       ((ffi/cfn "ret_uint8_max" [] :uint8))
                       ((ffi/cfn "ret_int16_neg" [] :int16))
                       ((ffi/cfn "ret_uint16_max" [] :uint16))
                       ((ffi/cfn "ret_float" [] :float))])))))))

(deftest varargs-lib-test
  (when-let [lib @test-lib]
    (testing "the callee's va_list sees the variadic args in order"
      (is (= [321 5261]
             (bb `(do ~(lib-require lib)
                      [((ffi/cfn "va_sum" [:long :&] :long) 3 1 2 3)
                       ;; 1 + 6*10 + (long)(13.0*4)*100
                       ((ffi/cfn "va_ld" [:long :&] :long) 1 6 13.0)])))))))

(deftest callback-lib-test
  (when-let [lib @test-lib]
    (testing "callbacks with typed args, including declared-order un-permutation"
      (is (= [30 24.5 24.5]
             (bb `(do ~(lib-require lib)
                      (let [jj# (ffi/callback (fn [a# b#] (* a# b#)) [:long :long] :long)
                            jd# (ffi/callback (fn [l# d#] (+ l# (* 2 d#))) [:long :double] :double)
                            ;; declared double-then-long: C passes (d0, x0),
                            ;; the wrapper must un-permute back to declared order
                            dj# (ffi/callback (fn [d# l#] (+ (* 2 d#) l#)) [:double :long] :double)]
                        [((ffi/cfn "cb_apply_jj" [:pointer :long :long] :long) jj# 5 6)
                         ((ffi/cfn "cb_apply_jd" [:pointer :long :double] :double) jd# 4 10.25)
                         ((ffi/cfn "cb_apply_dj" [:pointer :double :long] :double) dj# 10.25 4)])))))))
  (when-let [lib @test-lib]
    (testing "callback invoked from a C-created thread the runtime never saw"
      (is (= 42 (bb `(do ~(lib-require lib)
                         (let [cb# (ffi/callback (fn [a# b#] (* a# b#)) [:long :long] :long)]
                           ((ffi/cfn "cb_call_on_thread" [:pointer :long :long] :long)
                            cb# 21 2))))))))
  (when-let [lib @test-lib]
    (testing "free-callback releases; freeing twice or freeing unknown is a no-op"
      (is (= [42 nil nil]
             (bb `(do ~(lib-require lib)
                      (let [cb# (ffi/callback (fn [a# b#] (+ a# b#)) [:long :long] :long)
                            res# ((ffi/cfn "cb_apply_jj" [:pointer :long :long] :long) cb# 40 2)]
                        [res# (ffi/free-callback cb#) (ffi/free-callback cb#)]))))))))

(deftest backend-test
  (when-not skip?
    (testing "in a native image, non-variadic in-family shapes must use the
              compiled trampoline backend - a fallback to the interpreted FFM
              path is a 75x performance regression"
      (is (= (if tu/native? [:trampoline :trampoline :ffm] [:ffm :ffm :ffm])
             (bb `(do ~ffi-require
                      (mapv (comp :babashka.ffi/backend meta)
                            [(ffi/cfn "abs" [:int] :int)
                             (ffi/cfn "ldexp" [:double :int] :double)
                             ;; variadic stays on FFM by design
                             (ffi/cfn ~snprintf-sym
                                      [:pointer :size_t :string :&]
                                      :int)]))))))))

(deftest perf-canary-test
  ;; not a benchmark: a coarse ceiling that only the trampoline-to-FFM cliff
  ;; (~4800ns/call interpreted vs ~64ns compiled) can trip on a noisy runner
  (when (and (not skip?) tu/native?)
    (let [ns-per-call (bb `(do ~ffi-require
                               (let [f# (ffi/cfn "abs" [:int] :int)]
                                 (f# -1)
                                 (let [t0# (System/nanoTime)]
                                   (loop [i# 0]
                                     (when (< i# 200000) (f# (- i#)) (recur (inc i#))))
                                   (quot (- (System/nanoTime) t0#) 200000)))))]
      (is (< ns-per-call 1000)
          (str "native ffi call took " ns-per-call
               "ns - trampoline dispatch may have regressed to interpreted FFM")))))

(deftest darwin-alias-test
  (when-not (or skip? tu/windows?)
    (testing "load-library accepts :darwin as :mac (jolt compatibility)"
      (is (= 5 (bb `(do (require '[babashka.ffi :as ~'ffi])
                        (ffi/load-library {:darwin "libz.dylib" :linux "libz.so.1"})
                        ((ffi/cfn "compressBound" [:ulong] :ulong) 0)
                        ((ffi/cfn "strlen" [:string] :size_t) "hello"))))))))

(deftest metadata-generated-test
  (when-not skip?
    ;; skipped on Windows: a CRLF checkout would fail the byte comparison
    (when-not tu/windows?
      (testing "committed ffi reachability metadata matches the generator"
        (let [f "resources/META-INF/native-image/babashka/ffi/reachability-metadata.json"
              before (slurp f)]
          (load-file "script/gen_ffi_metadata.clj")
          (is (= before (slurp f))
              "run bb script/gen_ffi_metadata.clj and commit the result"))))))
