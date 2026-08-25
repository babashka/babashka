;; This example uses the same libffi bootstrap as libffi.clj. An arena owns
;; each allocation instead of a bare ffi/alloc.
;;
;; libffi.clj allocates 34 memory blocks and does not free them. These pointers
;; live until the process stops. This example closes the arena to release all
;; memory. If the body throws, the arena also releases the memory.
;;
;;   bb examples/ffi/libffi_arena.clj

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "ffi")

(defcfn prep-cif "ffi_prep_cif" [:pointer :int :uint :pointer :pointer] :int)
(defcfn ffi-call "ffi_call" [:pointer :pointer :pointer :pointer] :void)
(defcfn c-dlsym "dlsym" [:pointer :string] :pointer)

(def RTLD-DEFAULT
  (if (= "Mac OS X" (System/getProperty "os.name")) -2 0))

(defn sym-addr [name]
  (let [p (c-dlsym RTLD-DEFAULT name)]
    (when (ffi/null? p)
      (throw (ex-info (str "symbol not found: " name) {})))
    p))

;; ffi_type: {size_t size; unsigned short alignment; unsigned short type;
;;            ffi_type **elements} - 24 bytes on 64-bit
(defn ffi-type [arena size align code elements]
  (let [t (ffi/alloc arena 24)]
    (ffi/write t :size_t 0 size)
    (ffi/write t :uint16 8 align)
    (ffi/write t :uint16 10 code)
    (ffi/write t :pointer 16 elements)
    t))

(defn struct-type
  "Returns an FFI_TYPE_STRUCT for the specified element types. prep_cif sets
  its size and alignment."
  [arena element-types]
  (let [elems (ffi/alloc arena (* 8 (inc (count element-types))))]
    (doseq [[i t] (map-indexed vector element-types)]
      (ffi/write elems :pointer (* 8 i) t))
    (ffi/write elems :pointer (* 8 (count element-types)) 0)
    (ffi-type arena 0 0 13 elems)))

(def FFI-DEFAULT-ABI
  ;; On aarch64, FFI_SYSV is 1. On x86-64, FFI_UNIX64 is 2.
  (if (= "aarch64" (System/getProperty "os.arch")) 1 2))

(defn make-cif [arena ret-type arg-types]
  (let [n (count arg-types)
        atypes (ffi/alloc arena (max 8 (* 8 n)))
        cif (ffi/alloc arena 128)]
    (doseq [[i t] (map-indexed vector arg-types)]
      (ffi/write atypes :pointer (* 8 i) t))
    (when-not (zero? (prep-cif cif FFI-DEFAULT-ABI n ret-type atypes))
      (throw (ex-info "ffi_prep_cif failed" {})))
    cif))

;; -- struct-by-value return: div_t div(int, int) ------------------------------

;; Each pointer that follows belongs to this arena. This includes the pointers
;; that struct-type and make-cif allocate.
(with-open [arena (ffi/confined-arena)]
  (let [t-sint32 (ffi-type arena 4 4 10 0)
        t-div (struct-type arena [t-sint32 t-sint32])
        cif (make-cif arena t-div [t-sint32 t-sint32])
        fnp (sym-addr "div")
        a0 (ffi/alloc arena :int)
        a1 (ffi/alloc arena :int)
        avalues (ffi/alloc arena 16)
        rvalue (ffi/alloc arena 8)]
    (ffi/write avalues :pointer 0 a0)
    (ffi/write avalues :pointer 8 a1)
    (ffi/write a0 :int 0 7)
    (ffi/write a1 :int 0 2)
    (ffi-call cif fnp rvalue avalues)
    (println "div(7, 2) =" {:quot (ffi/read rvalue :int 0)
                            :rem (ffi/read rvalue :int 4)}
             (if (= [3 1] [(ffi/read rvalue :int 0) (ffi/read rvalue :int 4)])
               "OK" "FAIL"))))

;; -- Benchmark: Compare ldexp through libffi and cfn. -------------------------

(def N 200000)

(with-open [arena (ffi/confined-arena)]
  (let [t-double (ffi-type arena 8 8 3 0)
        t-sint32 (ffi-type arena 4 4 10 0)
        cif (make-cif arena t-double [t-double t-sint32])
        fnp (sym-addr "ldexp")
        a0 (ffi/alloc arena :double)
        a1 (ffi/alloc arena :int)
        avalues (ffi/alloc arena 16)
        rvalue (ffi/alloc arena 8)]
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
      (println "libffi:    " (quot (- (System/nanoTime) t0) N) "ns/call"))))

(let [ldexp (ffi/cfn "ldexp" [:double :int] :double)]
  (ldexp 1.5 3)
  (let [t0 (System/nanoTime)]
    (loop [i 0]
      (when (< i N)
        (ldexp 1.5 i)
        (recur (inc i))))
    (println "trampoline:" (quot (- (System/nanoTime) t0) N) "ns/call")))

;; -- Measure the arena cost. Each call creates one scope. ---------------------

(let [t0 (System/nanoTime)]
  (loop [i 0]
    (when (< i 50000)
      (with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena :int)]
          (ffi/write p :int 0 i)
          (ffi/read p :int 0)))
      (recur (inc i))))
  (println "arena per scope, 1 alloc:" (quot (- (System/nanoTime) t0) 50000) "ns"))

(let [t0 (System/nanoTime)]
  (loop [i 0]
    (when (< i 50000)
      (let [p (ffi/alloc 4)]
        (try (ffi/write p :int 0 i)
             (ffi/read p :int 0)
             (finally (ffi/free p))))
      (recur (inc i))))
  (println "malloc/free, 1 alloc:    " (quot (- (System/nanoTime) t0) 50000) "ns"))

(println "LIBFFI ARENA OK")
