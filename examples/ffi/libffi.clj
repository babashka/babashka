;; libffi bound through babashka.ffi itself: ffi_prep_cif and ffi_call are
;; plain pointer/int signatures, so the bounded FFI can bootstrap an
;; unbounded one. Demonstrates a struct-by-value RETURN (libc div_t div(int,
;; int)), impossible with scalar bindings, and benchmarks a libffi call
;; against the trampoline path.

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "ffi")

(defcfn prep-cif "ffi_prep_cif" [:pointer :int :uint :pointer :pointer] :int)
(defcfn ffi-call "ffi_call" [:pointer :pointer :pointer :pointer] :void)
(defcfn c-dlsym "dlsym" [:pointer :string] :pointer)

(def RTLD-DEFAULT
  ;; a pseudo handle, not a real address, so it is written as a raw one
  (ffi/segment (if (= "Mac OS X" (System/getProperty "os.name")) -2 0)))

(defn sym-addr [name]
  (let [p (c-dlsym RTLD-DEFAULT name)]
    (when (ffi/null? p)
      (throw (ex-info (str "symbol not found: " name) {})))
    p))

;; ffi_type: {size_t size; unsigned short alignment; unsigned short type;
;;            ffi_type **elements} - 24 bytes on 64-bit
(defn ffi-type [size align code elements]
  (let [t (ffi/alloc 24)]
    (ffi/write t :size_t 0 size)
    (ffi/write t :uint16 8 align)
    (ffi/write t :uint16 10 code)
    (ffi/write t :pointer 16 elements)
    t))

(def t-double (ffi-type 8 8 3 nil))    ; FFI_TYPE_DOUBLE
(def t-sint32 (ffi-type 4 4 10 nil))   ; FFI_TYPE_SINT32

(defn struct-type
  "An FFI_TYPE_STRUCT of the given element types; prep_cif fills in size
  and alignment."
  [element-types]
  (let [elems (ffi/alloc (* 8 (inc (count element-types))))]
    (doseq [[i t] (map-indexed vector element-types)]
      (ffi/write elems :pointer (* 8 i) t))
    (ffi/write elems :pointer (* 8 (count element-types)) ffi/null)
    (ffi-type 0 0 13 elems)))

(def FFI-DEFAULT-ABI
  ;; aarch64 FFI_SYSV = 1; x86-64 FFI_UNIX64 = 2
  (if (= "aarch64" (System/getProperty "os.arch")) 1 2))

(defn make-cif [ret-type arg-types]
  (let [n (count arg-types)
        atypes (ffi/alloc (max 8 (* 8 n)))
        cif (ffi/alloc 128)]
    (doseq [[i t] (map-indexed vector arg-types)]
      (ffi/write atypes :pointer (* 8 i) t))
    (when-not (zero? (prep-cif cif FFI-DEFAULT-ABI n ret-type atypes))
      (throw (ex-info "ffi_prep_cif failed" {})))
    cif))

;; -- struct-by-value return: div_t div(int, int) ------------------------------

(let [t-div (struct-type [t-sint32 t-sint32])
      cif (make-cif t-div [t-sint32 t-sint32])
      fnp (sym-addr "div")
      a0 (ffi/alloc 4)
      a1 (ffi/alloc 4)
      avalues (ffi/alloc 16)
      rvalue (ffi/alloc 8)]
  (ffi/write avalues :pointer 0 a0)
  (ffi/write avalues :pointer 8 a1)
  (ffi/write a0 :int 0 7)
  (ffi/write a1 :int 0 2)
  (ffi-call cif fnp rvalue avalues)
  (println "div(7, 2) =" {:quot (ffi/read rvalue :int 0)
                          :rem (ffi/read rvalue :int 4)}
           (if (= [3 1] [(ffi/read rvalue :int 0) (ffi/read rvalue :int 4)])
             "OK" "FAIL")))

;; -- benchmark: ldexp via libffi vs via cfn (trampoline) ----------------------

(def N 200000)

(let [cif (make-cif t-double [t-double t-sint32])
      fnp (sym-addr "ldexp")
      a0 (ffi/alloc 8)
      a1 (ffi/alloc 4)
      avalues (ffi/alloc 16)
      rvalue (ffi/alloc 8)]
  (ffi/write avalues :pointer 0 a0)
  (ffi/write avalues :pointer 8 a1)
  (ffi/write a0 :double 0 1.5)
  (ffi/write a1 :int 0 3)
  (ffi-call cif fnp rvalue avalues)
  (println "ldexp via libffi =" (ffi/read rvalue :double 0))
  (let [t0 (System/nanoTime)]
    (loop [i 0]
      (when (< i N)
        (ffi/write a0 :double 0 1.5)
        (ffi/write a1 :int 0 i)
        (ffi-call cif fnp rvalue avalues)
        (ffi/read rvalue :double 0)
        (recur (inc i))))
    (println "libffi:    " (quot (- (System/nanoTime) t0) N) "ns/call")))

(let [ldexp (ffi/cfn "ldexp" [:double :int] :double)]
  (ldexp 1.5 3)
  (let [t0 (System/nanoTime)]
    (loop [i 0]
      (when (< i N)
        (ldexp 1.5 i)
        (recur (inc i))))
    (println "trampoline:" (quot (- (System/nanoTime) t0) N) "ns/call")))

(println "LIBFFI OK")
