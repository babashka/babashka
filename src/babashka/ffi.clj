(ns babashka.ffi
  "Foreign function interface over java.lang.foreign.

  Load a shared library, bind C functions with explicit argument and return
  types, and marshal memory by hand:

      (require '[babashka.ffi :as ffi])
      (ffi/load-system-library \"sqlite3\")
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try (sqlite3-open \"x.db\" pp)
             (ffi/read pp :pointer)
             (finally (ffi/free pp))))

  Types: :void :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
  :uint32 :int64 :uint64 :size_t :ssize_t :char :byte :bool :pointer
  :string :double :float. :bool is C's one-byte bool and returns true or
  false, so a C predicate does not come back as a truthy 0.

  Pointers are plain longs (machine addresses). Integer types are widened to
  a 64-bit carrier for the call and narrowed back on return, so a native
  image only needs a bounded family of function descriptors. :float keeps its
  exact layout (float ABI differs from double). Struct-by-value arguments are
  not supported.

  Signature limits (native image): up to 6 arguments, of which at most 6 are
  pointer or integer types; the floating-point arguments may be any mix of
  :double and :float up to three, or four when they are all the same type.
  Signatures of only pointer and integer types may have up to 10 arguments.
  A :float return needs 4 arguments or fewer. Variadic calls: up to 5
  arguments, at most 3 fixed, at most 2 :double. Callbacks: up to 4
  arguments, at most 2 :double, no :float, and a :void, integer, or :double
  return. Argument order does not matter, only how many of each kind there
  are. See doc/ffi.md for why these limits exist.

  A trailing :& declares a variadic C function: the types before it are the
  fixed parameters, and the tail types are inferred per call from the values
  (integers and pointers as 64-bit ints, floats as double per C promotion,
  strings as C strings):

      (ffi/defcfn c-open \"open\" [:string :int :&] :int)
      (c-open path O_RDONLY)         ; empty tail
      (c-open path flags 0644)       ; one-int tail, same binding"
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout
            MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle MethodHandles MethodType]))

(set! *warn-on-reflection* true)

;; Everything that touches Linker/handles is lazy: creating downcall handles or
;; upcall stubs during build-time class initialization is forbidden in a native
;; image (addresses would be baked into the image heap).
(def ^:private linker* (delay (Linker/nativeLinker)))

(def ^:private long-carrier?
  #{:int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32 :uint32
    :int64 :uint64 :size_t :ssize_t :char :byte :pointer :string :bool})

(defn- carrier [t]
  (cond (long-carrier? t) :long
        (= :double t) :double
        (= :float t) :float
        (= :void t) :void
        :else (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t}))))

(def ^:private carrier-layout
  {:long ValueLayout/JAVA_LONG
   :double ValueLayout/JAVA_DOUBLE
   :float ValueLayout/JAVA_FLOAT})

(defn- check-variadic-marker
  "Validates use of the :& variadic marker. Returns the fixed types (the
  vector without the trailing :&) for a variadic signature, nil for a plain
  one."
  [argtypes]
  (when (some #(= :& %) (butlast argtypes))
    (throw (ex-info "babashka.ffi: :& must be last; variadic tail types are inferred per call"
                    {:argtypes argtypes})))
  (when (= :& (peek argtypes))
    (let [fixed (pop argtypes)]
      (when (zero? (count fixed))
        (throw (ex-info "babashka.ffi: a variadic signature needs at least one fixed argtype before :&"
                        {:argtypes argtypes})))
      fixed)))

(defn- tail-type
  "The inferred type of one variadic tail value. Sound because C promotes
  variadic floats to double and small ints to int, and every integer width
  and pointer shares the 64-bit carrier."
  [v]
  (cond
    (or (integer? v) (nil? v) (boolean? v) (instance? MemorySegment v)) :long
    (float? v) :double
    (ratio? v) :double
    (string? v) :string
    :else (throw (ex-info (str "babashka.ffi: cannot infer variadic tail type of " (type v))
                          {:value v}))))

(defn- descriptor ^FunctionDescriptor [argtypes rettype]
  (let [args (into-array MemoryLayout (map #(carrier-layout (carrier %)) argtypes))]
    (if (= :void rettype)
      (FunctionDescriptor/ofVoid args)
      (FunctionDescriptor/of (carrier-layout (carrier rettype)) args))))

;; On the SysV x86-64 and AArch64 ABIs, integer and floating-point arguments
;; are assigned registers from two independent sequences (GP and FP), so
;; argument order BETWEEN those classes does not affect the calling
;; convention as long as nothing spills to the stack (<= 6 integer and <= 8
;; floating args). WITHIN the FP class, float and double share ONE register
;; sequence, so their relative order must be preserved: the sort moves
;; integer carriers first and keeps the floating args in declared order
;; (:double and :float have equal rank; the sort is stable). Not valid on
;; Windows x64 (positional registers) or for variadic calls
;; (stack-positional).
(def ^:private carrier-rank {:long 0 :double 1 :float 1})

(def ^:private windows?
  (.startsWith ^String (System/getProperty "os.name" "") "Windows"))

(defn- sort-permutation
  "Indices that stably sort types by carrier class, or nil when already
  sorted. Always nil on Windows, whose ABI assigns registers by position:
  there the descriptor must preserve the declared order, and the registered
  family holds ordered shapes (see script/gen_ffi_metadata.clj)."
  [types]
  (when-not windows?
    (let [perm (vec (sort-by (fn [i] [(carrier-rank (carrier (nth types i))) i])
                             (range (count types))))]
      (when-not (= perm (vec (range (count types))))
        perm))))

(defn- inverse-permutation [perm]
  (reduce (fn [inv p] (assoc inv (nth perm p) p))
          (vec (repeat (count perm) nil))
          (range (count perm))))

;; -- memory -------------------------------------------------------------------

(defn- segment ^MemorySegment [addr size]
  (.reinterpret (MemorySegment/ofAddress addr) (long size)))

(defn ptr->string
  "The NUL-terminated UTF-8 C string at pointer p, as a Clojure string."
  [p]
  (.getString (segment p Long/MAX_VALUE) 0))

(defn- with-string-args
  "Calls f with argtypes' :string args replaced by temp C-string pointers,
  freed after the call. Strings passed to C must not be retained by it."
  [argtypes args f]
  (if (some #(= :string %) argtypes)
    (with-open [arena (Arena/ofConfined)]
      (f (mapv (fn [t a]
                 (if (and (= :string t) (string? a))
                   (.address (.allocateFrom ^Arena arena ^String a))
                   a))
               argtypes args)))
    (f args)))

;; One coercion function per type, looked up when a binding is created, so
;; nothing dispatches on the type during a call.
(def ^:private arg-coercer
  (let [as-long (fn [a] (cond (nil? a) 0
                              (instance? MemorySegment a) (.address ^MemorySegment a)
                              :else (long a)))
        as-double (fn [a] (double a))
        as-float (fn [a] (float a))
        as-bool (fn [a] (if a 1 0))]
    (into {:double as-double :float as-float :bool as-bool}
          (map (fn [t] [t as-long]))
          (disj long-carrier? :bool))))

(defn- coerce-arg [t a] ((arg-coercer t) a))

(defn- narrow-ret [t raw]
  (case t
    :void nil
    :bool (not (zero? (long raw)))
    (:int :int32) (long (unchecked-int (long raw)))
    (:uint :uint32) (bit-and (long raw) 0xFFFFFFFF)
    :int16 (long (unchecked-short (long raw)))
    :uint16 (bit-and (long raw) 0xFFFF)
    (:int8 :byte :char) (long (unchecked-byte (long raw)))
    :uint8 (bit-and (long raw) 0xFF)
    :string (let [p (long raw)] (when-not (zero? p) (ptr->string p)))
    raw))

;; -- libraries ----------------------------------------------------------------

(def ^:private libraries (atom []))

(defn- os-key []
  (let [os (System/getProperty "os.name")]
    (cond (.startsWith ^String os "Mac") :mac
          (.startsWith ^String os "Windows") :windows
          :else :linux)))

(defn- search-dirs
  "Directories probed for bare library names after the system's own dlopen
  search fails."
  []
  (case (os-key)
    :mac ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib" "/usr/lib"]
    :windows []
    ["/usr/local/lib" "/usr/lib" "/usr/lib/x86_64-linux-gnu"
     "/usr/lib/aarch64-linux-gnu" "/lib"]))

(defn- try-lookup ^SymbolLookup [^String path]
  (try (SymbolLookup/libraryLookup path (Arena/global))
       (catch Throwable _ nil)))

(defn- lookup-one
  "One path through the full search: as given, then, for a bare name, the
  common install directories. A {:path :lookup} map, nil when not found."
  [^String path]
  (or (when-let [lk (try-lookup path)]
        {:path path :lookup lk})
      (when-not (.contains path "/")
        (some (fn [dir]
                (let [p (str dir "/" path)]
                  (when-let [lk (try-lookup p)]
                    {:path p :lookup lk})))
              (search-dirs)))))

(defn load-library
  "Loads a shared library and registers it for symbol resolution. Prefer
  load-system-library when the name only differs per OS by convention; this
  is the escape hatch for version-pinned names and absolute paths. Takes a
  path, a vector of candidate paths tried in order, or a map from OS
  keyword (:mac :linux :windows) to a path or such a vector:

      (ffi/load-library
        {:mac [\"/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib\"
               \"/usr/local/opt/openssl@3/lib/libcrypto.3.dylib\"]
         :linux \"libcrypto.so.3\"})

  :darwin is accepted as a synonym for :mac (jolt compatibility). A bare
  name (no separator) that the system's dlopen search does not find is also
  probed in common install directories (see search-dirs). Returns a map,
  usable as the first argument to cfn, whose :path is the candidate that
  loaded."
  [lib]
  (let [paths (cond
                (map? lib)
                (let [v (or (get lib (os-key))
                            (when (= :mac (os-key)) (get lib :darwin))
                            (throw (ex-info (str "babashka.ffi: no library for OS " (os-key))
                                            {:libs lib})))]
                  (mapv str (if (vector? v) v [v])))
                (vector? lib) (mapv str lib)
                :else [(str lib)])
        m (or (some lookup-one paths)
              (throw (ex-info (str "babashka.ffi: cannot load library: "
                                   (str/join ", " paths)
                                   " (bare names also searched in "
                                   (pr-str (vec (search-dirs))) ")")
                              {:library lib})))]
    (swap! libraries conj (:lookup m))
    m))

(defn load-system-library
  "Loads a library by its short name, e.g. \"z\" for libz.dylib / libz.so /
  z.dll, searching the system paths and common install directories (see
  load-library). On Linux, versioned sonames are found by globbing: the bare
  .so link only exists with the -dev package installed."
  [name]
  (case (os-key)
    :mac (load-library (str "lib" name ".dylib"))
    :windows (load-library (str name ".dll"))
    (let [base (str "lib" name ".so")]
      (or (try (load-library base) (catch Exception _ nil))
          ;; glob lib<name>.so.* in the search dirs
          (when-let [m (some (fn [dir]
                               (let [d (java.io.File. ^String dir)
                                     ;; newest soname first, numerically:
                                     ;; libz.so.10 beats libz.so.9
                                     vkey (fn [^String f]
                                            (mapv #(or (parse-long %) -1)
                                                  (rest (str/split (subs f (count base)) #"\."))))
                                     newest-first (fn [x y]
                                                    (let [a (vkey x) b (vkey y)
                                                          n (max (count a) (count b))
                                                          pad #(into % (repeat (- n (count %)) -1))]
                                                      (compare (pad b) (pad a))))
                                     cands (when (.isDirectory d)
                                             (->> (.list d)
                                                  (filter #(.startsWith ^String % (str base ".")))
                                                  (sort newest-first)))]
                                 (some (fn [c]
                                         (let [p (str dir "/" c)]
                                           (when-let [lk (try-lookup p)]
                                             {:path p :lookup lk})))
                                       cands)))
                             (search-dirs))]
            (swap! libraries conj (:lookup m))
            m)
          (throw (ex-info (str "babashka.ffi: cannot find library " name
                               " (tried " base " and " base ".* in "
                               (pr-str (vec (search-dirs))) ")")
                          {:library name}))))))

(defn- lookup-symbol ^MemorySegment [lib ^String sym]
  (let [lib (if (map? lib) (:lookup lib) lib)
        lookups (if lib [lib] (conj @libraries (.defaultLookup ^Linker @linker*)))]
    (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)))

(defn- require-symbol ^MemorySegment [lib ^String sym]
  (or (lookup-symbol lib sym)
      (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym}))))

(defn find-symbol
  "The address of symbol sym in the loaded libraries and the default (libc)
  lookup, as a pointer, or nil when not found."
  [sym]
  (some-> (lookup-symbol nil (str sym)) .address))

;; -- foreign functions --------------------------------------------------------

(def ^:private native-image?
  (boolean (System/getProperty "org.graalvm.nativeimage.imagecode")))

;; In a native image, FFM downcall handles are interpreted (~3.4us/call);
;; the generated trampolines (babashka.impl.FfiTrampoline) call through raw
;; function pointers as compiled direct calls (~2ns). One per canonical
;; shape; loaded only in the image, never on the JVM, where the FFM handle
;; path is JIT-compiled and fast.
(def ^:private trampoline-ids
  (when native-image?
    @(requiring-resolve 'babashka.impl.ffi-trampolines/ids)))

(def ^:private trampoline-invoker
  (when native-image?
    (requiring-resolve 'babashka.impl.ffi-trampolines/invoker)))

(defn- shape-key [types* rettype]
  (let [c {:long "J" :double "D" :float "F"}]
    (str (if (= :void rettype) "V" (c (carrier rettype)))
         "_"
         (apply str (map #(c (carrier %)) types*)))))

(defn- unsupported-ex [sym argtypes rettype why]
  (ex-info (str "babashka.ffi: unsupported signature: " sym " "
                (pr-str argtypes) " -> " rettype ". " why ". "
                "Workaround: call through libffi (ffi-libffi.clj in the babashka repo shows how). "
                "Please report this signature in a babashka issue; it can likely be supported.")
           {:symbol sym :argtypes argtypes :rettype rettype}))

(def ^:private variadic-limits
  "variadic calls support up to 5 args total, at most 3 fixed, at most 2 :double, and a :void, integer or pointer return")

(declare ^:private fixed-cfn)

(defn- variadic-cfn
  "A variadic binding: fixed types declared, tail inferred per call. One FFM
  handle per distinct tail shape, cached."
  [lib sym fixed argtypes rettype]
  (doseq [t fixed] (carrier t))
  (carrier rettype)
  (when (and native-image?
             (or (> (count fixed) 3)
                 (some #(= :float (carrier %)) fixed)
                 ;; variadic descriptors are only registered for void and
                 ;; integer returns
                 (#{:double :float} (carrier rettype))))
    (throw (unsupported-ex sym argtypes rettype variadic-limits)))
  (let [nf (count fixed)
        cache (atom {})
        caller-for
        (fn [tail-types]
          (or (get @cache tail-types)
              (let [all-types (into fixed tail-types)]
                (when (and native-image?
                           (or (> (count all-types) 5)
                               (> (count (filter #(= :double (carrier %)) all-types)) 2)))
                  (throw (unsupported-ex sym argtypes rettype
                                         (str variadic-limits ", called with tail "
                                              (pr-str tail-types)))))
                (let [handle (.downcallHandle
                              ^Linker @linker*
                              (require-symbol lib sym)
                              (descriptor all-types rettype)
                              (into-array java.lang.foreign.Linker$Option
                                          [(java.lang.foreign.Linker$Option/firstVariadicArg nf)]))
                      caller (fn [^objects arr]
                               (.invokeWithArguments ^MethodHandle handle arr))]
                  (swap! cache assoc tail-types caller)
                  caller))))]
    (with-meta
      (fn [& args]
        (when (< (count args) nf)
          (throw (ex-info (str "babashka.ffi: " sym " expects at least " nf
                               " args, got " (count args))
                          {:symbol sym})))
        (let [args (vec args)
              tail-types (mapv tail-type (subvec args nf))
              all-types (into fixed tail-types)
              caller (caller-for tail-types)]
          (with-string-args all-types args
            (fn [args]
              (narrow-ret rettype
                          (caller (object-array
                                   (map-indexed (fn [i a] (coerce-arg (all-types i) a))
                                                args))))))))
      {:babashka.ffi/backend :ffm})))

(defn cfn
  "Binds C function sym as a Clojure function. argtypes is a vector of type
  keywords, rettype a type keyword. With a lib (the map returned by
  load-library) the symbol is resolved in that library only; without, in all
  loaded libraries and then the default (libc) lookup. The handle is created
  on first call, so binding may precede load-library. A trailing :& declares
  the C function variadic: the types before it are the fixed parameters, the
  tail is inferred per call."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when-not (string? sym)
     (throw (ex-info (str "babashka.ffi: C symbol must be a string: " (pr-str sym))
                     {:sym sym})))
   (if-let [fixed (check-variadic-marker argtypes)]
     (variadic-cfn lib sym fixed argtypes rettype)
     (fixed-cfn lib sym argtypes rettype))))

(defn- fixed-cfn
  [lib sym argtypes rettype]
  (let [types argtypes
        perm (sort-permutation types)
        types* (if perm (mapv types perm) types)
        ;; raw invoker: a fn of the coerced argument array. In a native
        ;; image a generated trampoline (compiled direct call) when the
        ;; shape has one; otherwise an FFM downcall handle.
        tramp-id (get trampoline-ids (shape-key types* rettype))
        ;; in a native image every supported shape is known ahead of time,
        ;; so reject unsupported signatures here with a useful message
        ;; instead of GraalVM's rebuild-the-image error at call time
        _ (when (and native-image? (not tramp-id))
            (throw (unsupported-ex sym argtypes rettype
                                   "see the signature limits in doc/ffi.md")))
        raw (if tramp-id
              (delay (trampoline-invoker tramp-id (.address (require-symbol lib sym))))
              (delay
                (let [handle (.downcallHandle ^Linker @linker*
                                              (require-symbol lib sym)
                                              (descriptor types* rettype)
                                              (make-array java.lang.foreign.Linker$Option 0))]
                  (fn [^objects arr] (.invokeWithArguments ^MethodHandle handle arr)))))
         n (count types)
         strings? (boolean (some #(= :string %) types*))
         coercers ^objects (object-array (map arg-coercer types*))
         call (fn [^objects arr]
                (narrow-ret rettype ((force raw) arr)))
         ;; `in` holds the arguments as written; the call needs them in
         ;; descriptor order, coerced. Without a permutation that is done in
         ;; place, with one it fills a second array through the permutation,
         ;; and either way nothing allocates a seq or a vector.
         perm-arr (when perm (int-array perm))
         fill (if perm-arr
                (fn ^objects [^objects in]
                  (let [out (object-array n)]
                    (dotimes [i n]
                      (aset out i ((aget coercers i) (aget in (aget ^ints perm-arr i)))))
                    out))
                (fn ^objects [^objects in]
                  (dotimes [i n]
                    (aset in i ((aget coercers i) (aget in i))))
                  in))
         coerce-all (fn ^objects [args] (fill (object-array args)))
         ;; strings need a temporary arena that has to outlive the call
         general (fn [args]
                   (let [args* (if perm (mapv (vec args) perm) (vec args))]
                     (with-string-args types* args*
                       (fn [args]
                         (let [arr (object-array args)]
                           (dotimes [i n]
                             (aset arr i ((aget coercers i) (aget arr i))))
                           (call arr))))))
         arity-error (fn [got]
                       (throw (ex-info (str "babashka.ffi: " sym " expects " n
                                            " args, got " got)
                                       {:symbol sym})))]
     (with-meta
       (if strings?
         (fn [& args]
           (if (= (count args) n) (general args) (arity-error (count args))))
         ;; fixed arities, no seq allocation, no intermediate vectors
         (case n
             0 (fn [] (call (object-array 0)))
             1 (fn [a] (call (fill (doto (object-array 1) (aset 0 a)))))
             2 (fn [a b] (call (fill (doto (object-array 2) (aset 0 a) (aset 1 b)))))
             3 (fn [a b d] (call (fill (doto (object-array 3)
                                        (aset 0 a) (aset 1 b) (aset 2 d)))))
             4 (fn [a b d e] (call (fill (doto (object-array 4)
                                           (aset 0 a) (aset 1 b)
                                           (aset 2 d) (aset 3 e)))))
             (fn [& args]
               (if (= (count args) n) (call (coerce-all args)) (arity-error (count args))))))
       ;; which call mechanism this binding uses, for tests and diagnostics:
       ;; :trampoline = compiled direct call, :ffm = downcall handle
       ;; (interpreted in a native image)
       {:babashka.ffi/backend (if tramp-id :trampoline :ffm)})))

(defmacro defcfn
  "Defs a foreign function bound with cfn:

      (defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int)

      (defcfn sqlite3-open
        \"Opens the database at path, storing the handle in out-param pp.\"
        \"sqlite3_open\" [:string :pointer] :int)

  The last three arguments are always the C symbol, the argument types and
  the return type; an optional docstring and attribute map may precede
  them. Metadata on name is kept, so ^:private works."
  {:arglists '([name docstring? attr-map? sym argtypes rettype])}
  [name & args]
  (when (< (count args) 3)
    (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                    {:name name})))
  (let [[sym argtypes rettype] (take-last 3 args)
        prefix (drop-last 3 args)
        docstring (first (filter string? prefix))
        attr-map (first (filter map? prefix))]
    (when-not (and (<= (count prefix) 2)
                   (<= (count (filter string? prefix)) 1)
                   (every? #(or (string? %) (map? %)) prefix))
      (throw (ex-info "babashka.ffi: defcfn takes at most a docstring and an attribute map before the C symbol"
                      {:name name})))
    `(def ~(with-meta name (cond-> (meta name)
                             attr-map (merge attr-map)
                             docstring (assoc :doc docstring)))
       (cfn ~sym ~argtypes ~rettype))))

;; -- manual memory ------------------------------------------------------------

(def ^:private c-calloc (delay (cfn "calloc" [:size_t :size_t] :pointer)))
(def ^:private c-free (delay (cfn "free" [:pointer] :void)))

(defn alloc
  "Allocates n bytes of zeroed foreign memory. Returns the pointer. Free it
  with free."
  [n]
  (@c-calloc 1 n))

(defn free
  "Frees a pointer returned by alloc or string->ptr."
  [p]
  (@c-free p))

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(defn sizeof
  "Size in bytes of a type keyword."
  [t]
  (or (sizes t) (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t}))))

(defn read
  "Reads a typed value from pointer p at byte offset (default 0)."
  ([p t] (read p t 0))
  ([p t offset]
   (let [seg (segment p (+ (long offset) (long (sizeof t))))
         off (long offset)]
     (case t
       (:int :int32) (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off))
       (:uint :uint32) (bit-and (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off)) 0xFFFFFFFF)
       (:long :ulong :int64 :uint64 :size_t :ssize_t :pointer)
       (.get seg ValueLayout/JAVA_LONG_UNALIGNED off)
       :int16 (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off))
       :uint16 (bit-and (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off)) 0xFFFF)
       :bool (not (zero? (long (.get seg ValueLayout/JAVA_BYTE off))))
      (:int8 :byte :char) (long (.get seg ValueLayout/JAVA_BYTE off))
       :uint8 (bit-and (long (.get seg ValueLayout/JAVA_BYTE off)) 0xFF)
       :double (.get seg ValueLayout/JAVA_DOUBLE_UNALIGNED off)
       :float (.get seg ValueLayout/JAVA_FLOAT_UNALIGNED off)
       :string (ptr->string (.get seg ValueLayout/JAVA_LONG_UNALIGNED off))
       (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t}))))))

(defn write
  "Writes a typed value to pointer p at byte offset (default 0)."
  ([p t v] (write p t 0 v))
  ([p t offset v]
   (let [seg (segment p (+ (long offset) (long (sizeof t))))
         off (long offset)]
     (case t
       (:int :uint :int32 :uint32) (.set seg ValueLayout/JAVA_INT_UNALIGNED off (unchecked-int (long v)))
       (:long :ulong :int64 :uint64 :size_t :ssize_t :pointer)
       (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (long v))
       (:int16 :uint16) (.set seg ValueLayout/JAVA_SHORT_UNALIGNED off (unchecked-short (long v)))
       :bool (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (if v 1 0)))
       (:int8 :uint8 :byte :char) (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (long v)))
       :double (.set seg ValueLayout/JAVA_DOUBLE_UNALIGNED off (double v))
       :float (.set seg ValueLayout/JAVA_FLOAT_UNALIGNED off (float v))
       (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t})))
     nil)))

(defn string->ptr
  "Copies s to freshly allocated foreign memory as a NUL-terminated UTF-8 C
  string. Returns the pointer. Free it with free."
  [^String s]
  (let [bytes (.getBytes s "UTF-8")
        n (inc (alength bytes))
        p (alloc n)
        seg (segment p n)]
    (MemorySegment/copy bytes 0 seg ValueLayout/JAVA_BYTE 0 (alength bytes))
    p))

(def null
  "The NULL pointer."
  0)

(defn null?*
  "True if pointer p is NULL."
  [p]
  (zero? (long p)))

;; -- callbacks ----------------------------------------------------------------

(def ^:private callback-arenas (atom {}))

(defn callback
  "Wraps Clojure function f as a C function pointer so C can call back into
  Clojure (qsort comparators, signal handlers). argtypes/rettype use the same
  type keywords as cfn; long-carrier arguments arrive as longs (pointers as
  addresses). Returns the function pointer as a long. The callback stays
  alive until free-callback is called on the pointer."
  [f argtypes rettype]
  (let [n (count argtypes)
        perm (sort-permutation argtypes)
        inv (when perm (inverse-permutation perm))
        argtypes (if perm (mapv argtypes perm) argtypes)
        f (if perm
            (fn [& sorted]
              (let [sorted (vec sorted)]
                (apply f (map (fn [j] (nth sorted (nth inv j))) (range n)))))
            f)
        ret-carrier (carrier rettype)
        obj-type (MethodType/methodType Object ^"[Ljava.lang.Class;"
                                        (into-array Class (repeat n Object)))
        target-type (MethodType/methodType
                     ^Class (case ret-carrier
                              :void Void/TYPE :long Long/TYPE
                              :double Double/TYPE :float Float/TYPE)
                     ^"[Ljava.lang.Class;"
                     (into-array Class (map #(case (carrier %)
                                               :long Long/TYPE
                                               :double Double/TYPE
                                               :float Float/TYPE)
                                            argtypes)))
        mh (-> (MethodHandles/publicLookup)
               (.findVirtual clojure.lang.IFn "invoke" obj-type)
               (.bindTo f)
               (.asType target-type))
        arena (Arena/ofShared)
        stub (.upcallStub ^Linker @linker* mh (descriptor argtypes rettype)
                          arena
                          (make-array java.lang.foreign.Linker$Option 0))
        addr (.address stub)]
    (swap! callback-arenas assoc addr arena)
    addr))

(defn free-callback
  "Releases callback pointer p: frees the native stub and lets the wrapped fn
  be garbage collected. C must not call p afterwards. Unknown pointers are
  ignored."
  [p]
  (when-let [^Arena a (get @callback-arenas p)]
    (swap! callback-arenas dissoc p)
    (.close a))
  nil)
