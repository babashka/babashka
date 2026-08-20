(ns babashka.ffi-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
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
    (is (= 5 (bb `(do ~ffi-require
                      (~'defcfn ~'strlen "strlen" [:string] :size_t)
                      (~'strlen "hello")))))))

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
    (testing "snprintf through the variadic calling convention"
      (is (= "x-42" (bb `(do ~ffi-require
                             (let [buf (ffi/alloc 64)]
                               ((ffi/cfn ~snprintf-sym [:pointer :size_t :string :varargs :string :long] :int)
                                buf 64 "%s-%ld" "x" 42)
                               (let [s (ffi/ptr->string buf)]
                                 (ffi/free buf)
                                 s)))))))
    (testing "marker validation"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:varargs :string] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:string :varargs] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:string :varargs :float] :int))))))))

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
    (testing "missing library throws"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/load-library "libdoesnotexist-bb.so"))))))
    (testing "missing symbol throws"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      ((ffi/cfn "bb_no_such_symbol" [] :void)))))))))

(deftest error-test
  (when-not skip?
    (testing "unknown type keyword"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "abs" [:intt] :int))))))
    (testing "wrong argument count"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      ((ffi/cfn "abs" [:int] :int) 1 2))))))))

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
