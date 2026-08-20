(ns babashka.ffi
  "Foreign function interface over java.lang.foreign.

  Load a shared library, bind C functions with explicit argument and return
  types, and marshal memory by hand:

      (require '[babashka.ffi :as ffi])
      (ffi/load-library {:mac \"libsqlite3.dylib\" :linux \"libsqlite3.so.0\"})
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try (sqlite3-open \"x.db\" pp)
             (ffi/read pp :pointer)
             (finally (ffi/free pp))))

  Types: :void :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
  :uint32 :int64 :uint64 :size_t :ssize_t :char :byte :pointer :string
  :double :float.

  Pointers are plain longs (machine addresses). Integer types are widened to
  a 64-bit carrier for the call and narrowed back on return, so a native
  image only needs a bounded family of function descriptors. :float keeps its
  exact layout (float ABI differs from double). Struct-by-value arguments and
  variadic functions are not supported."
  (:refer-clojure :exclude [read])
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
    :int64 :uint64 :size_t :ssize_t :char :byte :pointer :string})

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

(defn- descriptor ^FunctionDescriptor [argtypes rettype]
  (let [args (into-array MemoryLayout (map #(carrier-layout (carrier %)) argtypes))]
    (if (= :void rettype)
      (FunctionDescriptor/ofVoid args)
      (FunctionDescriptor/of (carrier-layout (carrier rettype)) args))))

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

(defn- coerce-arg [t a]
  (case (carrier t)
    :long (cond (nil? a) 0
                (instance? MemorySegment a) (.address ^MemorySegment a)
                :else (long a))
    :double (double a)
    :float (float a)))

(defn- narrow-ret [t raw]
  (case t
    :void nil
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

(defn load-library
  "Loads a shared library and registers it for symbol resolution. Takes a
  path, or a map from OS keyword (:mac :linux :windows) to path. Returns the
  library's SymbolLookup, usable as the first argument to cfn."
  [path-or-map]
  (let [path (if (map? path-or-map)
               (or (get path-or-map (os-key))
                   (throw (ex-info (str "babashka.ffi: no library for OS " (os-key))
                                   {:libs path-or-map})))
               path-or-map)
        lookup (SymbolLookup/libraryLookup ^String (str path) (Arena/global))]
    (swap! libraries conj lookup)
    lookup))

(defn load-system-library
  "Loads a library by its short name, e.g. \"z\" for libz.dylib / libz.so /
  z.dll. See load-library."
  [name]
  (load-library (case (os-key)
                  :mac (str "lib" name ".dylib")
                  :windows (str name ".dll")
                  (str "lib" name ".so"))))

(defn- find-symbol ^MemorySegment [lib ^String sym]
  (let [lookups (if lib [lib] (conj @libraries (.defaultLookup ^Linker @linker*)))]
    (or (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)
        (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym})))))

;; -- foreign functions --------------------------------------------------------

(defn cfn
  "Binds C function sym as a Clojure function. argtypes is a vector of type
  keywords, rettype a type keyword. With a lib (from load-library) the symbol
  is resolved there; without, in all loaded libraries and then the default
  (libc) lookup. The handle is created on first call, so binding may precede
  load-library."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (let [handle (delay (.downcallHandle ^Linker @linker*
                                        (find-symbol lib sym)
                                        (descriptor argtypes rettype)
                                        (make-array java.lang.foreign.Linker$Option 0)))]
     (fn [& args]
       (when-not (= (count args) (count argtypes))
         (throw (ex-info (str "babashka.ffi: " sym " expects " (count argtypes)
                              " args, got " (count args))
                         {:symbol sym})))
       (with-string-args argtypes (vec args)
         (fn [args]
           (narrow-ret rettype
                       (.invokeWithArguments ^MethodHandle @handle
                                             ^java.util.List (mapv coerce-arg argtypes args)))))))))

(defmacro defcfn
  "(defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int) — defs a
  foreign function bound with cfn."
  [name sym argtypes rettype]
  `(def ~name (cfn ~sym ~argtypes ~rettype)))

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
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1})

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
       (:int8 :byte :char) (long (.get seg ValueLayout/JAVA_BYTE off))
       :uint8 (bit-and (long (.get seg ValueLayout/JAVA_BYTE off)) 0xFF)
       :double (.get seg ValueLayout/JAVA_DOUBLE_UNALIGNED off)
       :float (.get seg ValueLayout/JAVA_FLOAT_UNALIGNED off)
       :string (ptr->string (.get seg ValueLayout/JAVA_LONG_UNALIGNED off))
       (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t}))))))

(defn write
  "Writes a typed value to pointer p at byte offset."
  [p t offset v]
  (let [seg (segment p (+ (long offset) (long (sizeof t))))
        off (long offset)]
    (case t
      (:int :uint :int32 :uint32) (.set seg ValueLayout/JAVA_INT_UNALIGNED off (unchecked-int (long v)))
      (:long :ulong :int64 :uint64 :size_t :ssize_t :pointer)
      (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (long v))
      (:int16 :uint16) (.set seg ValueLayout/JAVA_SHORT_UNALIGNED off (unchecked-short (long v)))
      (:int8 :uint8 :byte :char) (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (long v)))
      :double (.set seg ValueLayout/JAVA_DOUBLE_UNALIGNED off (double v))
      :float (.set seg ValueLayout/JAVA_FLOAT_UNALIGNED off (float v))
      (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t})))
    nil))

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

(defn callback
  "Wraps Clojure function f as a C function pointer so C can call back into
  Clojure (qsort comparators, signal handlers). argtypes/rettype use the same
  type keywords as cfn; long-carrier arguments arrive as longs (pointers as
  addresses). Returns the function pointer as a long. The callback stays
  alive for the process lifetime."
  [f argtypes rettype]
  (let [n (count argtypes)
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
        stub (.upcallStub ^Linker @linker* mh (descriptor argtypes rettype)
                          (Arena/global)
                          (make-array java.lang.foreign.Linker$Option 0))]
    (.address stub)))
