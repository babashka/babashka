(require '[babashka.ffi :as ffi])

;; downcall, int widening + narrowing
(def c-abs (ffi/cfn "abs" [:int] :int))
(println "abs(-42) =" (c-abs -42) (if (= 42 (c-abs -42)) "OK" "FAIL"))

;; string arg + long return
(def c-strlen (ffi/cfn "strlen" [:string] :size_t))
(println "strlen =" (c-strlen "hello babashka") (if (= 14 (c-strlen "hello babashka")) "OK" "FAIL"))

;; string return
(def c-getenv (ffi/cfn "getenv" [:string] :string))
(println "getenv(HOME) =" (c-getenv "HOME") (if (= (System/getenv "HOME") (c-getenv "HOME")) "OK" "FAIL"))

;; float exactness
(def c-sqrtf (ffi/cfn "sqrtf" [:float] :float))
(println "sqrtf(9) =" (c-sqrtf 9.0) (if (= 3.0 (c-sqrtf 9.0)) "OK" "FAIL"))

;; double
(def c-pow (ffi/cfn "pow" [:double :double] :double))
(println "pow(2,10) =" (c-pow 2 10) (if (= 1024.0 (c-pow 2 10)) "OK" "FAIL"))

;; manual memory: out-param pattern via memcpy
(def c-memcpy (ffi/cfn "memcpy" [:pointer :pointer :size_t] :pointer))
(let [src (ffi/string->ptr "abc")
      dst (ffi/alloc 4)]
  (c-memcpy dst src 4)
  (println "memcpy roundtrip =" (ffi/ptr->string dst)
           (if (= "abc" (ffi/ptr->string dst)) "OK" "FAIL"))
  (ffi/free src) (ffi/free dst))

;; read/write
(let [p (ffi/alloc 16)]
  (ffi/write p :int 0 -7)
  (ffi/write p :double 8 1.5)
  (println "read/write =" [(ffi/read p :int) (ffi/read p :double 8)]
           (if (= [-7 1.5] [(ffi/read p :int) (ffi/read p :double 8)]) "OK" "FAIL"))
  (ffi/free p))

;; varargs: snprintf through the variadic calling convention
(def c-snprintf (ffi/cfn "snprintf" [:pointer :size_t :string :&] :int))
(let [buf (ffi/alloc 64)]
  (c-snprintf buf 64 "%s-%ld" "x" 42)
  (println "snprintf/varargs =" (ffi/ptr->string buf)
           (if (= "x-42" (ffi/ptr->string buf)) "OK" "FAIL"))
  (ffi/free buf))

;; callback: qsort comparator over int array
(def c-qsort (ffi/cfn "qsort" [:pointer :size_t :size_t :pointer] :void))
(let [n 5
      arr (ffi/alloc (* n 4))]
  (doseq [[i v] (map-indexed vector [5 3 1 4 2])]
    (ffi/write arr :int (* i 4) v))
  (let [cmp (ffi/callback (fn [pa pb]
                            (compare (ffi/read pa :int) (ffi/read pb :int)))
                          [:pointer :pointer] :int)]
    (c-qsort arr n 4 cmp)
    (let [sorted (mapv #(ffi/read arr :int (* % 4)) (range n))]
      (println "qsort/callback =" sorted (if (= [1 2 3 4 5] sorted) "OK" "FAIL"))))
  (ffi/free arr))

(println "SMOKE DONE")
