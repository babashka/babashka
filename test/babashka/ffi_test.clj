(ns babashka.ffi-test
  (:require
   [babashka.ffi]
   [babashka.process :as p]
   [babashka.test-utils :as tu]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
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
  ;; the musl static binary has no dlopen and no FFM default lookup; the
  ;; mostly-static aarch64 build keeps glibc dynamic and runs the suite
  (= "true" (System/getenv "BABASHKA_MUSL")))

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
                            (~'defcfn ~'bad "a" "b" "strlen" [:string] :size_t)))))
      (is (thrown? Exception
                   (bb `(do ~ffi-require
                            (~'defcfn ~'bad {:a 1} {:b 2} "strlen" [:string] :size_t))))))))

(deftest bool-test
  (when-not skip?
    (testing "a C bool returns true or false, not a truthy 0"
      (is (= [true false]
             (bb `(do ~ffi-require
                      (let [alpha?# (ffi/cfn "isalpha" [:int] :bool)]
                        [(alpha?# 97) (alpha?# 49)]))))))
    (testing "a bool argument takes Clojure truthiness"
      (is (= [1 0 1 0]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 4)]
                        (ffi/write p# :bool 0 true)
                        (ffi/write p# :bool 1 false)
                        (ffi/write p# :bool 2 :truthy)
                        (ffi/write p# :bool 3 nil)
                        (let [res# (mapv #(ffi/read p# :uint8 %) (range 4))]
                          (ffi/free p#)
                          res#)))))))
    (testing "reading a bool back"
      (is (= [true false]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 2)]
                        (ffi/write p# :bool 0 true)
                        (ffi/write p# :bool 1 false)
                        (let [res# [(ffi/read p# :bool 0) (ffi/read p# :bool 1)]]
                          (ffi/free p#)
                          res#)))))))))

(deftest segment-test
  (when-not skip?
    (testing "alloc returns a sized MemorySegment"
      (is (= [true 8]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 8)
                            res# [(ffi/pointer? p#) (ffi/size p#)]]
                        (ffi/free p#)
                        res#))))))
    (testing "read rejects an access past the end"
      (is (thrown-with-msg?
           Exception #"IndexOutOfBounds|Out of bound"
           (bb `(do ~ffi-require
                    (let [p# (ffi/alloc 4)]
                      (try (ffi/read p# :long 0)
                           (finally (ffi/free p#)))))))))
    (testing "read rejects a zero-size pointer from C"
      (is (= [0 "has size 0" "abc" "has size 0" 97]
             (bb `(do ~ffi-require
                      (let [src# (ffi/string->ptr "abc")
                            ;; strchr returns a zero-size pointer into src.
                            hit# ((ffi/cfn "strchr" [:pointer :int] :pointer) src# (int \a))
                            msg# (fn [f#] (try (f#) (catch Exception e# (re-find #"has size 0" (ex-message e#)))))
                            res# [(ffi/size hit#)
                                  (msg# #(ffi/ptr->string hit#))
                                  (ffi/ptr->string (ffi/reinterpret hit# 4))
                                  (msg# #(ffi/read hit# :char))
                                  (ffi/read (ffi/reinterpret hit# 1) :char)]]
                        (ffi/free src#)
                        res#))))))
    (testing "read and write reject zero-size segments"
      (is (= ["has size 0" "has size 0"]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 4)
                            z# (ffi/alloc 0)
                            msg# (fn [f#] (try (f#) (catch Exception e# (re-find #"has size 0" (ex-message e#)))))
                            res# [(msg# #(ffi/read (ffi/slice p# 4) :int))
                                  (msg# #(ffi/write z# :int 0 1))]]
                        (ffi/free p#)
                        (ffi/free z#)
                        res#))))))
    (testing "segment, address, slice and reinterpret"
      (is (= [true 8 4 42 4]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 8)
                            a# (ffi/address p#)
                            again# (ffi/segment a# 8)
                            tail# (ffi/slice p# 4)
                            _# (ffi/write tail# :int 0 42)
                            sized# (ffi/reinterpret (ffi/segment a#) 4)
                            res# [(= a# (ffi/address again#))
                                  (ffi/size again#)
                                  (ffi/size tail#)
                                  (ffi/read p# :int 4)
                                  (ffi/size sized#)]]
                        (ffi/free p#)
                        res#))))))
    (testing "a pointer argument rejects a number"
      (is (thrown-with-msg?
           Exception #"expected a pointer \(a MemorySegment\), got 42"
           (bb `(do ~ffi-require
                    (ffi/read 42 :int))))))
    (testing "null is a segment and null? detects each zero address"
      (is (= [true true false]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 1)
                            res# [(ffi/pointer? ffi/null)
                                  (ffi/null? (ffi/segment 0))
                                  (ffi/null? p#)]]
                        (ffi/free p#)
                        res#))))))))

(deftest heap-segment-test
  ;; A babashka script cannot create a heap segment.
  (testing "C pointer operations reject a heap segment"
    (let [heap (java.lang.foreign.MemorySegment/ofArray (byte-array 4))]
      (is (false? (babashka.ffi/pointer? heap)))
      (is (thrown-with-msg? Exception #"heap MemorySegment" (babashka.ffi/address heap)))
      ;; The JDK checks the bounds of heap segments.
      (is (= 0 (babashka.ffi/read heap :int)))
      (is (thrown-with-msg? Exception #"heap MemorySegment"
                            (babashka.ffi/cfn (java.lang.foreign.MemorySegment/ofArray (byte-array 8)) [:int] :int)))
      (when-not tu/windows?
        (is (thrown-with-msg? Exception #"heap MemorySegment"
                              ((babashka.ffi/cfn "strlen" [:pointer] :size_t) heap))))))
  (testing "a pointer from a closed arena is refused before C sees it"
    (let [arena (java.lang.foreign.Arena/ofConfined)
          p (.allocate arena 8)]
      (.close arena)
      (is (false? (babashka.ffi/pointer? p)))
      (is (thrown-with-msg? Exception #"closed arena" (babashka.ffi/address p)))
      (is (thrown-with-msg? Exception #"closed arena" (babashka.ffi/cfn p [:int] :int)))
      (when-not tu/windows?
        (is (thrown-with-msg? Exception #"closed arena"
                              ((babashka.ffi/cfn "strlen" [:pointer] :size_t) p))))))
  (testing "a pointer of a confined arena is refused on another thread"
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [p (.allocate arena 8)
            msg (fn [f] (try (f) (catch Exception e (re-find #"another thread" (ex-message e)))))
            on-other-thread (fn [f] @(future (f)))]
        (is (true? (babashka.ffi/pointer? p)))
        (is (false? (on-other-thread #(babashka.ffi/pointer? p))))
        (is (= "another thread" (on-other-thread #(msg (fn [] (babashka.ffi/address p))))))
        (when-not tu/windows?
          (let [strlen (babashka.ffi/cfn "strlen" [:pointer] :size_t)]
            (is (= "another thread" (on-other-thread #(msg (fn [] (strlen p))))))))))))

(deftest arena-test
  (when-not skip?
    (testing "with-open reads values from arena allocations"
      (is (= [42 7]
             (bb `(do ~ffi-require
                      (with-open [a# (ffi/confined-arena)]
                        (let [p# (ffi/alloc a# :pointer)
                              q# (ffi/alloc a# 16)]
                          (ffi/write p# :long 0 42)
                          (ffi/write q# :int 4 7)
                          [(ffi/read p# :long) (ffi/read q# :int 4)])))))))
    (testing "all arena kinds allocate"
      (is (= [true true true true]
             (bb `(do ~ffi-require
                      [(with-open [a# (ffi/confined-arena)] (instance? java.lang.foreign.MemorySegment (ffi/alloc a# 8)))
                       (with-open [a# (ffi/shared-arena)] (instance? java.lang.foreign.MemorySegment (ffi/alloc a# 8)))
                       (instance? java.lang.foreign.MemorySegment (ffi/alloc (ffi/auto-arena) 8))
                       (instance? java.lang.foreign.MemorySegment (ffi/alloc (ffi/global-arena) 8))])))))
    (testing "alloc accepts a type or size"
      (is (= 8 (bb `(do ~ffi-require
                        (with-open [a# (ffi/confined-arena)]
                          (let [p# (ffi/alloc a# :pointer)]
                            (ffi/write p# :long 0 -1)
                            (count (ffi/read-bytes p# 8)))))))))
    (testing "arena allocations stay aligned after odd-sized allocations"
      ;; Arena.allocate(byteSize) guarantees only alignment 1. Thus, alloc
      ;; supplies natural alignment for a type and alignment 16 for a byte count.
      (is (= [0 0 0 0]
             (bb `(do ~ffi-require
                      (with-open [a# (ffi/confined-arena)]
                        (ffi/alloc a# :int8)
                        (let [d# (ffi/alloc a# :double)
                              _# (ffi/alloc a# 3)
                              p# (ffi/alloc a# :pointer)
                              _# (ffi/alloc a# :int8)
                              i# (ffi/alloc a# :int)
                              _# (ffi/alloc a# 1)
                              b# (ffi/alloc a# 24)]
                          [(mod d# 8) (mod p# 8) (mod i# 4) (mod b# 16)])))))))))

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
    (testing "a NULL char* reads as nil"
      (is (= [nil nil]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 8)]
                        (let [res# [(ffi/read p# :string) (ffi/ptr->string ffi/null)]]
                          (ffi/free p#)
                          res#)))))))
    (testing "read-bytes and write-bytes use the specified offset"
      (is (= [[1 2 3 4] [0 0]]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 16)]
                        (ffi/write-bytes p# (byte-array [1 2 3 4]) 2)
                        (let [res# [(vec (ffi/read-bytes p# 4 2))
                                    (vec (ffi/read-bytes p# 2))]]
                          (ffi/free p#)
                          res#)))))))
    (testing "byte-buffer shares native memory without a copy"
      (is (= [7 42]
             (bb `(do ~ffi-require
                      (let [p# (ffi/alloc 8)
                            bb# (ffi/byte-buffer p# 8)]
                        (.put bb# 0 (byte 7))
                        (ffi/write p# :int8 1 42)
                        (let [res# [(ffi/read p# :int8) (long (.get bb# 1))]]
                          (ffi/free p#)
                          res#)))))))
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

(deftest address-test
  (when-not skip?
    (testing "cfn binds a function pointer"
      (is (= [42 42]
             (bb `(do ~ffi-require
                      (let [addr# (ffi/find-symbol "abs")]
                        [((ffi/cfn addr# [:int] :int) -42)
                         ;; The next call uses the function name.
                         ((ffi/cfn "abs" [:int] :int) -42)]))))))
    (testing "cfn calls a callback through its address"
      (is (= 42
             (bb `(do ~ffi-require
                      (let [cb# (ffi/callback (fn [x#] (* x# 3)) [:int] :int)]
                        ((ffi/cfn cb# [:int] :int) 14)))))))
    (testing "cfn rejects the null address at bind time"
      (is (thrown? Exception (bb `(do ~ffi-require (ffi/cfn 0 [:int] :int))))))))

(deftest callback-test
  (when-not skip?
    (testing "qsort comparator upcall"
      (is (= [1 2 3 4 5]
             (bb `(do ~ffi-require
                      (let [arr (ffi/alloc 20)]
                        (doseq [[i# v#] (map-indexed vector [5 3 1 4 2])]
                          (ffi/write arr :int (* i# 4) v#))
                        (let [cmp (ffi/callback
                                   ;; qsort gives zero-size pointers to the comparator.
                                   (fn [pa# pb#]
                                     (compare (ffi/read (ffi/reinterpret pa# 4) :int)
                                              (ffi/read (ffi/reinterpret pb# 4) :int)))
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
      (testing "the returned map holds the resolved path and scopes cfn"
        (is (true? (bb `(do ~ffi-require
                            (let [z# (ffi/load-system-library "z")]
                              (and (string? (:path z#))
                                   (string? ((ffi/cfn z# "zlibVersion" [] :string)))))))))))
    (when-not tu/windows?
    (testing "candidate vectors in the OS map, first that loads wins"
      (is (string? (bb `(do (require '[babashka.ffi :as ~'ffi])
                            (ffi/load-library
                             {:mac ["nonexistent-bb-zzz.dylib" "libz.dylib"]
                              :linux ["nonexistent-bb-zzz.so" "libz.so.1" "libz.so"]})
                            ((ffi/cfn "zlibVersion" [] :string))))))))
    (when-not tu/windows?
      (testing "a top-level candidate vector works like an OS map value"
        (is (string? (bb `(do (require '[babashka.ffi :as ~'ffi])
                              (ffi/load-library
                               ["nonexistent-bb-zzz.so" "libz.dylib" "libz.so.1"])
                              ((ffi/cfn "zlibVersion" [] :string))))))))
  (when (and tu/native?
             (= "Linux" (System/getProperty "os.name")))
    (when-let [lib @test-lib]
      (testing "the soname glob searches LD_LIBRARY_PATH directories"
        (let [dir (io/file "target" "ffi-env-lib")
              f (io/file dir "libbbtest.so.1")]
          (io/make-parents f)
          (io/copy (io/file lib) f)
          (let [res @(p/process
                      ["./bb" "-e"
                       (pr-str '(do (require '[babashka.ffi :as ffi])
                                    (print (str (:path (ffi/load-system-library "bbtest"))))))]
                      {:out :string :err :string
                       :extra-env {"LD_LIBRARY_PATH" (.getAbsolutePath dir)}})]
            (is (zero? (:exit res)) (:err res))
            (is (re-find #"libbbtest\.so\.1$" (:out res))))))))
  (testing "find-symbol probes without binding"
    (is (= [true nil]
           (bb `(do (require '[babashka.ffi :as ~'ffi])
                    [(ffi/pointer? (ffi/find-symbol "strlen"))
                     (ffi/find-symbol "bb_no_such_symbol_zzz")])))))
  (when-let [lib @test-lib]
    (testing "a library map limits the search to that library"
      ;; mix_dj is loaded. Thus, the search without a library map finds it.
      ;; The test library does not define zlibVersion and does not link zlib.
      ;; Zlib does not link the test library. Thus, each map excludes the
      ;; symbol from the other library. A library map includes its
      ;; dependencies. The selected symbols do not occur in these dependencies.
      (is (= [true true nil nil]
             (bb `(do ~ffi-require
                      (let [t# (ffi/load-library ~lib)
                            z# (ffi/load-system-library "z")]
                        [(some? (ffi/find-symbol "mix_dj"))
                         (some? (ffi/find-symbol t# "mix_dj"))
                         (ffi/find-symbol z# "mix_dj")
                         (ffi/find-symbol t# "zlibVersion")])))))))
  (testing "cfn accepts a library map, delay or function"
    ;; Windows has no zlib, so the C runtime stands in. strlen resolves in
    ;; both: msvcrt defines it, and a library map searches the libraries
    ;; zlib links, which include the C library
    (is (= [5 5 5]
           (bb `(do ~ffi-require
                    (let [lib# (ffi/load-system-library ~(if tu/windows? "msvcrt" "z"))
                          strlen# (fn [l#] ((ffi/cfn l# "strlen" [:string] :size_t) "hello"))]
                      (mapv strlen# [lib# (delay lib#) (fn [] lib#)])))))))
  (testing "an invalid :library value causes a clear error"
    ;; false is not "no library" and a keyword or a collection is not a
    ;; function to call, although both are IFn
    (doseq [bad ["not a library" false :a-keyword [1 2] #{:a}]]
      (is (thrown-with-msg?
           Exception #":library must be a library map"
           (bb `(do ~ffi-require
                    ((ffi/cfn ~bad "zlibVersion" [] :string)))))
          (pr-str bad))))
  (testing "a variadic binding asks a :library function once, not per tail shape"
    ;; two tail shapes, one and two integers: each gets its own handle. No
    ;; floats, since %f follows the C locale and prints "1,5" under nl_NL
    (is (= [1 "1" "2-3"]
           (bb `(do ~ffi-require
                    (let [calls# (atom 0)
                          ;; zlib links the C library, and a library map
                          ;; searches the libraries it links, so snprintf
                          ;; resolves through it on every platform
                          lib# (fn [] (swap! calls# inc)
                                 (ffi/load-system-library ~(if tu/windows? "msvcrt" "z")))
                          snprintf# (ffi/cfn lib# ~snprintf-sym [:pointer :size_t :string :&] :int)
                          buf# (ffi/alloc 32)
                          fmt# (fn [f# & vs#]
                                 (apply snprintf# buf# 32 f# vs#)
                                 (ffi/ptr->string buf#))
                          a# (fmt# "%d" 1)
                          b# (fmt# "%d-%d" 2 3)]
                      (ffi/free buf#)
                      [@calls# a# b#]))))))
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
                    ((ffi/cfn "printf" [:string :&] :int) "%d %d %d %d %d" 1 2 3 4 5)))))
      (is (thrown-with-msg?
           Exception #"unsupported signature"
           (bb `(do (require '[babashka.ffi :as ~'ffi])
                    (ffi/cfn "printf" [:string :&] :double))))))))

(deftest error-test
  (when-not skip?
    (testing "unknown type keyword"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "abs" [:intt] :int))))))
    (testing "wrong argument count"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      ((ffi/cfn "abs" [:int] :int) 1 2))))))
    (testing ":void is not an argument type, on any path"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "abs" [:void] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "printf" [:string :void :&] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/callback (fn [a#] a#) [:void] :long))))))
    (testing "reserved spellings fail at bind time, not at call time"
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn (symbol "abs") [:int] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "abs" [:coffi.mem/int] :int)))))
      (is (thrown? Exception (bb `(do ~ffi-require
                                      (ffi/cfn "div" [:int :int]
                                               [:struct [:int :int]]))))))))

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
    (testing "callback results are coerced: booleans and boxed ints crossing
              the upcall boundary uncoerced would kill the VM"
      (is (= [1 0 3 7]
             (bb `(do ~(lib-require lib)
                      (let [apply# (ffi/cfn "cb_apply_jj" [:pointer :long :long] :long)
                            bool# (ffi/callback (fn [a# b#] (< a# b#)) [:long :long] :bool)
                            int# (ffi/callback (fn [a# b#] (int (+ a# b#))) [:long :long] :int)]
                        [(apply# bool# 1 2) (apply# bool# 2 1)
                         (apply# int# 1 2) (apply# int# 3 4)]))))))
    (testing "bool callback arguments arrive as booleans"
      (is (= [1 0]
             (bb `(do ~(lib-require lib)
                      (let [apply# (ffi/cfn "cb_apply_jj" [:pointer :long :long] :long)
                            cb# (ffi/callback (fn [a# b#] (if (and a# (not b#)) 1 0))
                                              [:bool :bool] :long)]
                        [(apply# cb# 1 0) (apply# cb# 0 1)])))))))
  (when-not skip?
    (testing "out-of-family callback shapes fail at creation"
      (is (= (if tu/native? :threw :ok)
             (bb `(do ~ffi-require
                      (try (ffi/callback (fn [a# b# c# d# e#] 0)
                                         [:long :long :long :long :long] :long)
                           :ok
                           (catch Exception e#
                             (if (re-find #"unsupported signature" (ex-message e#))
                               :threw
                               :wrong-error)))))))))
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
              path is a 75x performance regression. Windows has ordered
              trampolines, so mixed shapes compile there too"
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

(def ^:private generated-files
  ["resources/META-INF/native-image/babashka/ffi/reachability-metadata.json"
   "src-java/babashka/impl/FfiTrampoline.java"
   "src/babashka/impl/ffi_trampolines.clj"])

(deftest metadata-generated-test
  (when-not skip?
    ;; skipped on Windows: a CRLF checkout would fail the byte comparison
    (when-not tu/windows?
      (testing "committed generated ffi sources match the generator"
        (let [before (mapv slurp generated-files)]
          (load-file "script/gen_ffi_metadata.clj")
          (doseq [[f b] (map vector generated-files before)]
            (is (= b (slurp f))
                (str f ": run bb script/gen_ffi_metadata.clj and commit the result")))))
      (testing "windows mode: ordered trampolines, no fixed FFM descriptors"
        (let [before (mapv slurp generated-files)]
          (try
            (binding [*command-line-args* '("windows")]
              (load-file "script/gen_ffi_metadata.clj"))
            (let [meta (json/parse-string (slurp (first generated-files)))
                  downcalls (get-in meta ["foreign" "downcalls"])
                  java-src (slurp (second generated-files))]
              (testing "every registered downcall is variadic"
                (is (seq downcalls))
                (is (every? #(get % "options") downcalls)))
              (testing "upcalls respect the 2-double family limit"
                (is (every? #(<= (count (filter #{"jdouble"} (get % "parameterTypes"))) 2)
                            (get-in meta ["foreign" "upcalls"]))))
              (testing "ordered shapes get trampolines, out-of-family ones do not"
                (is (str/includes? java-src "interface F_D_DJ "))
                (is (str/includes? java-src "interface F_J_JJJJJJJJJJ "))
                (is (str/includes? java-src "interface F_V_JJDDDD "))
                (is (not (str/includes? java-src "interface F_V_DDDFJ ")))))
            (finally
              (doseq [[f b] (map vector generated-files before)]
                (spit f b)))))))))
