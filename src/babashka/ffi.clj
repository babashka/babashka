(ns babashka.ffi
  "Call functions in native shared libraries.

  Load a library, bind C functions with explicit argument and return types,
  and manage native memory:

      (require '[babashka.ffi :as ffi])
      (ffi/load-system-library \"sqlite3\")
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (with-open [arena (ffi/confined-arena)]
        (let [pp (ffi/alloc arena :pointer)]
          (sqlite3-open \"x.db\" pp)
          (ffi/read pp :pointer)))

  Every allocation belongs to an arena. The arena controls the lifetime of
  the memory.

  Use these type keywords:

      :void
      :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
      :uint32 :int64 :uint64 :size_t :ssize_t :char :byte
      :bool :pointer :string :double :float

  A pointer is a native java.lang.foreign.MemorySegment with a size. read and
  write check each access against this size. Pointers from C have size zero.
  reinterpret specifies their size before access. :bool
  represents a one-byte C boolean and returns true or false. Thus, a C
  predicate does not return the truthy number 0.

  A function that takes a struct as an argument, or returns one, without a
  pointer in between, gets a layout on that position in the signature. A
  struct value is a map of its fields:

      (ffi/defcfn c-div \"div\" [:int :int] [:struct [[:quot :int] [:rem :int]]])
      (c-div 7 2)   ;=> {:quot 3 :rem 1}

  Struct calls use libffi. See doc/ffi.md.

  Native images compile a fixed set of fast call shapes: up to six
  arguments, at most three mixed floating-point arguments or four of the
  same floating-point type, up to 10 integer or pointer arguments, and a
  :float return with up to four arguments. A fixed signature outside this
  set calls through libffi, at about 1 microsecond instead of about 100
  nanoseconds. Without libffi, such a signature throws.

  Native images use libffi for every variadic call. If libffi is not
  available, variadic calls use the FFM fallback. This fallback supports at
  most five total arguments, three fixed arguments, none of them :float,
  and two :double arguments. Its return type must be :void, an integer
  type, or a pointer type. Callbacks
  support up to four arguments and two :double arguments. Callbacks do not
  support :float. The callback return type must be :void, an integer type, or
  :double. Argument order does not affect these limits. See doc/ffi.md for
  details and workarounds.

  Add a trailing :& to declare a variadic C function. The types before :& are
  the fixed parameters. Each call infers the tail types from the values.
  Integers and pointers use 64-bit integers. C promotion converts floats to
  doubles. Strings use C strings:

      (ffi/defcfn c-open \"open\" [:string :int :&] :int)
      (c-open path O_RDONLY)         ; empty tail
      (c-open path flags 0644)       ; one-int tail, same binding"
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
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

(defn- pointer-ex [p]
  (ex-info (cond
             ;; A heap segment does not contain a C address.
             (and (instance? MemorySegment p) (not (.isNative ^MemorySegment p)))
             "babashka.ffi: expected a pointer to native memory, got a heap MemorySegment"
             ;; C can access released memory through a closed segment.
             (and (instance? MemorySegment p)
                  (not (.isAlive (.scope ^MemorySegment p))))
             (str "babashka.ffi: the pointer at address " (.address ^MemorySegment p)
                  " belongs to a closed arena")
             ;; a confined arena is for one thread; another thread must not
             ;; hand its memory to C
             (instance? MemorySegment p)
             (str "babashka.ffi: the pointer at address " (.address ^MemorySegment p)
                  " belongs to an arena confined to another thread")
             :else
             (str "babashka.ffi: expected a pointer (a MemorySegment), got "
                  (pr-str p)
                  ". Wrap a raw address with (ffi/segment addr)"))
           {:value p}))

(defn- native-segment?
  "Returns true when p is a native MemorySegment with a live scope that this
  thread may access."
  [p]
  (and (instance? MemorySegment p)
       (.isNative ^MemorySegment p)
       (.isAlive (.scope ^MemorySegment p))
       (.isAccessibleBy ^MemorySegment p (Thread/currentThread))))

(defn- as-pointer
  "Returns p as a native MemorySegment.
  Rejects raw addresses, heap segments, and all other values."
  ^MemorySegment [p]
  (if (native-segment? p) p (throw (pointer-ex p))))

(defn- pointer-address
  "Returns the native address of p. Treats nil as the NULL pointer."
  [p]
  (cond (nil? p) 0
        (native-segment? p) (.address ^MemorySegment p)
        :else (throw (pointer-ex p))))

(defn- not-accessible-ex
  "Returns the error for a value that accessible rejects.
  This separate function permits JIT inlining of accessible."
  [p]
  (if (instance? MemorySegment p)
    (ex-info (str "babashka.ffi: the pointer at address " (.address ^MemorySegment p)
                  " has size 0; give it a size with reinterpret")
             {:pointer p})
    (pointer-ex p)))

(defn- accessible
  "Returns p as a nonzero MemorySegment. The JDK checks access against its size.
  Accepts heap segments because these operations do not pass an address to C."
  ^MemorySegment [p]
  (if (and (instance? MemorySegment p) (pos? (.byteSize ^MemorySegment p)))
    p
    (throw (not-accessible-ex p))))

(defn segment
  "Returns a pointer to addr. The default size is zero.
  A specified nonzero size enables bounds checks.

  CAUTION: Keep addr before size. A transposed call can stop the process at
  the first read."
  (^MemorySegment [addr] (MemorySegment/ofAddress (long addr)))
  (^MemorySegment [addr size]
   (.reinterpret (MemorySegment/ofAddress (long addr)) (long size))))

(defn reinterpret
  "Returns a view of segment seg with byte size size.

  Without an arena the view has an unbounded lifetime. That is correct for
  memory that C owns and that outlives your code.

  With an arena, the view is valid only while that arena is open. A read after
  the arena closes throws. The arena calls the optional cleanup function with
  the view when it closes. Use this function for a C library deallocator.

  CAUTION: Give the actual size. The runtime cannot know if this size is
  correct. A larger size permits out-of-bounds reads.

  CAUTION: If the arena is closed, do not pass the view to C. C can access the
  released memory."
  (^MemorySegment [seg size] (.reinterpret (as-pointer seg) (long size)))
  (^MemorySegment [seg size arena]
   (.reinterpret (as-pointer seg) (long size) ^Arena arena nil))
  (^MemorySegment [seg size arena cleanup]
   (.reinterpret (as-pointer seg) (long size) ^Arena arena
                 (reify java.util.function.Consumer
                   (accept [_ s] (cleanup s))))))

(declare sizeof)

(defn slice
  "Returns a slice of seg at byte offset. By default, the slice ends with seg.
  len is an integer byte count, a type keyword, or a struct layout, so walking
  an array of structs takes the layout itself:

      (slice arr (* i (sizeof point)) point)

  CAUTION: Keep offset before len. A transposed call throws only if the result
  does not fit in seg."
  (^MemorySegment [seg offset] (.asSlice (as-pointer seg) (long offset)))
  (^MemorySegment [seg offset len]
   (.asSlice (as-pointer seg) (long offset)
             (long (if (integer? len) len (sizeof len))))))

(defn address
  "Returns the native address of pointer p as a Clojure long."
  [p]
  (.address (as-pointer p)))

(defn size
  "Returns the size of pointer p in bytes. A pointer that C returned has
  size 0."
  [p]
  (.byteSize (as-pointer p)))

(defn pointer?
  "Returns true when x is a pointer: a MemorySegment of native memory."
  [x]
  (native-segment? x))

(defn- string-at
  "Returns the NUL-terminated UTF-8 string at addr. Returns nil for address zero."
  [^long addr]
  (when-not (zero? addr)
    (.getString (.reinterpret (MemorySegment/ofAddress addr) Long/MAX_VALUE) 0)))

(defn ptr->string
  "Returns the NUL-terminated UTF-8 string at p, read within the size of p.
  Returns nil for a NULL pointer.

  A pointer of size 0 is refused: give it a size with reinterpret, or declare
  the C return type as :string."
  [p]
  (let [seg (as-pointer p)]
    (when-not (zero? (.address seg))
      (.getString (accessible seg) 0))))

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
                              (native-segment? a) (.address ^MemorySegment a)
                              :else (long a)))
        as-addr (fn [a] (cond (nil? a) 0
                              (native-segment? a) (.address ^MemorySegment a)
                              :else (throw (pointer-ex a))))
        as-bool (fn [a] (if a 1 0))]
    (into {:double double :float float :bool as-bool :pointer as-addr}
          (map (fn [t] [t as-long]))
          (disj long-carrier? :bool :pointer))))

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
    :string (string-at (long raw))
    ;; the descriptor carries a pointer as a 64-bit integer, so the segment is
    ;; built here: zero-length, as the JDK hands one out
    :pointer (MemorySegment/ofAddress (long raw))
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
  search fails. On Linux LD_LIBRARY_PATH comes first: dlopen honors it by
  itself, but the versioned-soname glob cannot."
  []
  (case (os-key)
    :mac ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib" "/usr/lib"]
    :windows []
    (concat
     (when-let [p (System/getenv "LD_LIBRARY_PATH")]
       (remove str/blank? (str/split p #":")))
     (let [multiarch (if (= "aarch64" (System/getProperty "os.arch"))
                      "aarch64-linux-gnu"
                      "x86_64-linux-gnu")]
      ["/usr/local/lib"
       ;; RHEL family and the FreeBSD linux compat layer keep libraries here
       "/usr/lib64"
       "/usr/lib"
       (str "/usr/lib/" multiarch)
       ;; unmerged-/usr RHEL family and the FreeBSD linux compat layer
       "/lib64"
       "/lib"
       ;; unmerged-/usr systems keep runtime libraries here
       (str "/lib/" multiarch)]))))

(def ^:private last-lookup-error (volatile! nil))

(defn- try-lookup ^SymbolLookup [^String path]
  (try (SymbolLookup/libraryLookup path (Arena/global))
       (catch java.lang.IllegalCallerException e
         ;; native access denied: no candidate can ever load, so fail loud
         ;; instead of reporting a misleading not-found
         (throw (ex-info (str "babashka.ffi: native access is not enabled on this JVM: "
                              (ex-message e)
                              " (run with --enable-native-access=ALL-UNNAMED)")
                         {:path path} e)))
       (catch Throwable e
         (vreset! last-lookup-error e)
         nil)))

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
  "Loads a shared library and adds it to the symbol search.

  Use load-system-library for file names that follow platform conventions.

  lib can be a path, a vector of candidates, or a map of operating systems to
  candidates. The function tries vector entries in order. An operating-system
  map uses the keys :mac, :linux, and :windows:

      (ffi/load-library
        {:mac [\"/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib\"
               \"/usr/local/opt/openssl@3/lib/libcrypto.3.dylib\"]
         :linux \"libcrypto.so.3\"})

  :darwin is an alias for :mac. For a bare name, the function also searches
  common installation directories. Returns a library map whose :path value
  identifies the loaded candidate. The map can be the first argument to cfn.
  In that form, cfn searches only this library."
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
                              {:library lib}
                              @last-lookup-error)))]
    (swap! libraries conj (:lookup m))
    m))

(defn load-system-library
  "Loads a shared library by its short name. For example, \"z\" selects
  libz.dylib, libz.so, or z.dll. On Linux, the search also includes versioned
  names such as libz.so.1. Returns the same library map as load-library."
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
                          {:library name}
                          @last-lookup-error))))))

(defn- resolve-library
  "Returns the SymbolLookup for a :library value. The value can be a library
  map or a function that returns one. It can also be an IDeref object that
  holds a library map."
  ^SymbolLookup [lib]
  (let [;; fn? and not ifn?: a keyword, a vector or a set is an IFn too, and
        ;; calling one with no arguments gives an arity error instead of the
        ;; message below
        lib (cond (map? lib) lib
                  (instance? clojure.lang.IDeref lib) @lib
                  (fn? lib) (lib)
                  :else lib)
        lookup (if (map? lib) (:lookup lib) lib)]
    (if (instance? SymbolLookup lookup)
      lookup
      (throw (ex-info (str "babashka.ffi: :library must be a library map, a function that returns one, or a delay, atom or var that holds one, got "
                           (pr-str lib))
                      {:library lib})))))

(defn- lookup-symbol ^MemorySegment [lib ^String sym]
  (let [;; nil? and not truthiness: false is not "no library", it is a wrong one
        lookups (if (nil? lib)
                  (conj @libraries (.defaultLookup ^Linker @linker*))
                  [(resolve-library lib)])]
    (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)))

(defn- require-symbol ^MemorySegment [lib sym]
  (if (instance? MemorySegment sym)
    ;; The caller already resolved this function pointer.
    (as-pointer sym)
    (or (lookup-symbol lib ^String sym)
        (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym})))))

(defn find-symbol
  "Finds sym and returns a pointer to it. Returns nil for an unknown symbol.

  A library value limits the search to one library and its dependencies.
  Without a library value, find-symbol searches all loaded libraries. Then it
  searches the default system lookup."
  ([sym] (find-symbol nil sym))
  ([lib sym]
   (lookup-symbol lib (str sym))))

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
                "Workaround: call through libffi (examples/ffi/libffi.clj in the babashka repo shows how). "
                "Please report this signature in a babashka issue; it can likely be supported.")
           {:symbol sym :argtypes argtypes :rettype rettype}))

(def ^:private variadic-limits
  "variadic calls support up to 5 args total, at most 3 fixed and none of them :float, at most 2 :double, and a :void, integer or pointer return")

(def ^:private layout-kinds
  ;; Keep in sync with .clj-kondo/hooks/babashka/ffi.clj.
  #{:struct})

(defn- layout-vector? [t]
  (and (vector? t) (contains? layout-kinds (first t))))

(defn- struct-layout? [t]
  (and (vector? t) (= :struct (first t))))

(declare ^:private fixed-cfn ^:private fixed-ffm-cfn ^:private variadic-ffm-cfn
         ^:private libffi-cfn ^:private libffi-available?)

(defn- variadic-libffi-cfn
  "A variadic binding through libffi: one cif per distinct tail shape,
  cached. libffi applies the platform's variadic convention from
  ffi_prep_cif_var, so the limits of the registered FFM descriptors do not
  apply. The cache starts over past 64 shapes, so a binding that sees ever
  new tails does not hold native memory without bound; the garbage
  collector releases a dropped shape's cif."
  [lib sym fixed rettype]
  (let [nf (count fixed)
        cache (atom {})
        address (delay (.address (require-symbol lib sym)))]
    (with-meta
      (fn [& args]
        (when (< (count args) nf)
          (throw (ex-info (str "babashka.ffi: " sym " expects at least " nf
                               " args, got " (count args))
                          {:symbol sym})))
        (let [args (vec args)
              tail-types (mapv tail-type (subvec args nf))
              ;; a hit reads the atom; only a miss swaps, and a delay per
              ;; shape means two threads that miss at once build one cif
              f @(or (get @cache tail-types)
                     (get (swap! cache
                                 (fn [m]
                                   (cond (get m tail-types) m
                                         ;; past 64 shapes the cache starts
                                         ;; over; the GC frees dropped cifs
                                         :else (assoc (if (> (count m) 64) {} m)
                                                      tail-types
                                                      (delay (libffi-cfn lib sym (into fixed tail-types)
                                                                         rettype nf address))))))
                          tail-types))]
          (apply f args)))
      {:babashka.ffi/backend :libffi})))

(defn- variadic-cfn
  "A variadic binding: fixed types declared, tail inferred per call. In a
  native image the call goes through libffi (an FFM handle is interpreted
  there, which costs microseconds); on the JVM one FFM handle per distinct
  tail shape, cached."
  [lib sym fixed argtypes rettype]
  (doseq [t fixed] (carrier t))
  (carrier rettype)
  (if (and native-image? (libffi-available?))
    (variadic-libffi-cfn lib sym fixed rettype)
    (variadic-ffm-cfn lib sym fixed argtypes rettype)))

(defn- variadic-ffm-cfn
  [lib sym fixed argtypes rettype]
  (when (and native-image?
             (or (> (count fixed) 3)
                 (some #(= :float (carrier %)) fixed)
                 ;; variadic descriptors are only registered for void and
                 ;; integer returns
                 (#{:double :float} (carrier rettype))))
    (throw (unsupported-ex sym argtypes rettype variadic-limits)))
  (let [nf (count fixed)
        cache (atom {})
        ;; resolved once per binding, on the first call, and shared by every
        ;; tail shape: a :library function is asked for its library one time
        address (delay (require-symbol lib sym))
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
                              ^MemorySegment @address
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
  "Creates a Clojure function that calls the C function sym. sym is a C symbol
  name or a function pointer. argtypes is a vector of type keywords. rettype
  is a type keyword. A struct that the function takes as an argument, or
  returns, without a pointer in between, is a layout on that position, and
  its value is a map of its fields. Struct calls use libffi.

  Use a function pointer for a function that has no exported name. The pointer
  can come from a loader, C function, struct field, find-symbol, or callback.

  A library value limits the search to one library and its dependencies.
  Without a library value, cfn searches all loaded libraries. Then it searches
  the default system lookup. The first call resolves the symbol and creates
  the call handle. You can create the binding before you load its library.

  A trailing :& declares a variadic C function. The types before :& are the
  fixed parameters. Each call infers the tail types from its values."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when-not (or (string? sym) (native-segment? sym))
     (throw (if (instance? MemorySegment sym)
              (pointer-ex sym)
              (ex-info (str "babashka.ffi: C symbol must be a string or a pointer: "
                            (pr-str sym))
                       {:sym sym}))))
   ;; A null function pointer stops the process on the first call. A loader
   ;; returns this value when it does not have the requested function.
   (when (and (instance? MemorySegment sym) (zero? (.address ^MemorySegment sym)))
     (throw (ex-info "babashka.ffi: cannot bind the null address" {:sym sym})))
   (doseq [t argtypes]
     (when (= :void t)
       (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                       {:argtypes argtypes})))
     ;; A layout kind here usually means that argtypes and rettype are transposed.
     (when (contains? layout-kinds t)
       (throw (ex-info (str "babashka.ffi: " t " is a layout kind, not an argument type. "
                            "A layout goes in one type position as " (pr-str [t '...])
                            ". Make sure that argtypes and the return type are in the correct order: "
                            (pr-str argtypes))
                       {:argtypes argtypes :rettype rettype}))))
   (let [fixed (check-variadic-marker argtypes)
         ;; any vector on a type position is a layout; layout-of says which
         ;; kinds exist
         structs? (or (vector? rettype) (boolean (some vector? argtypes)))]
     (cond
       (and structs? fixed)
       (throw (ex-info (str "babashka.ffi: a variadic signature cannot pass a struct by value: " sym)
                       {:argtypes argtypes :rettype rettype}))
       structs? (libffi-cfn lib sym argtypes rettype)
       fixed (variadic-cfn lib sym fixed argtypes rettype)
       :else (fixed-cfn lib sym argtypes rettype)))))

(defn- fixed-cfn
  [lib sym argtypes rettype]
  (if (and native-image?
           (not (get trampoline-ids (shape-key (let [p (sort-permutation argtypes)]
                                                 (if p (mapv argtypes p) argtypes))
                                               rettype)))
           (libffi-available?))
    ;; no trampoline for this shape: libffi makes the call (~1us)
    (libffi-cfn lib sym argtypes rettype)
    (fixed-ffm-cfn lib sym argtypes rettype)))

(defn- fixed-ffm-cfn
  [lib sym argtypes rettype]
  (let [types argtypes
        perm (sort-permutation types)
        types* (if perm (mapv types perm) types)
        ;; raw invoker: a fn of the coerced argument array. In a native
        ;; image a generated trampoline (compiled direct call) when the
        ;; shape has one; otherwise an FFM downcall handle.
        tramp-id (get trampoline-ids (shape-key types* rettype))
        ;; in a native image every trampoline shape is known ahead of time
        ;; (ordered shapes on Windows, canonical elsewhere). A signature
        ;; without one calls through libffi; a build without libffi gets
        ;; a useful message instead of GraalVM's rebuild-the-image error
        ;; at call time
        _ (when (and native-image? (not tramp-id) (not (libffi-available?)))
            (throw (unsupported-ex sym argtypes rettype
                                   "this build has no libffi; see the signature limits in doc/ffi.md")))
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
  "Defines name as a C function binding created by cfn:

      (defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int)

      (defcfn sqlite3-open
        \"Opens the database at path, storing the handle in out-param pp.\"
        \"sqlite3_open\" [:string :pointer] :int)

  An optional docstring and attribute map can precede the C symbol. The final
  three arguments are the C symbol, argument types, and return type. defcfn
  preserves all metadata on name. This metadata includes ^:private.

  The :library key in the attribute map selects a library for cfn:

      (def sqlite (delay (ffi/load-library (extract-bundled-library!))))
      (defcfn sqlite3-open {:library sqlite} \"sqlite3_open\"
        [:string :pointer] :int)

  The value can be a library map or a function that returns one. It can also
  be an IDeref object that holds a library map.

  Without :library, a binding searches all loaded libraries. Then it searches
  the default system lookup. A system library with the same name can supply
  the symbol.

  The wrapper form binds the raw C function to a local name and defines name
  as the wrapper:

      (defcfn open-db
        \"sqlite3_open_v2\" [:string :pointer :int :string] :int
        open-native
        [filename flags]
        (with-open [arena (ffi/confined-arena)]
          (let [pdb (ffi/alloc arena :pointer)
                code (open-native filename pdb flags nil)]
            (if (zero? code)
              (ffi/read pdb :pointer)
              (throw (ex-info \"open failed\" {:code code}))))))

  The symbol after the return type names the raw binding. Only the wrapper
  body can use this name. The forms after the raw name are a normal fn tail.
  The wrapper can have multiple arities. Its argument lists can differ from
  the C function. The raw name does not enter the namespace. The wrapper
  form needs a literal argtypes vector. Only the plain form accepts an
  argtypes expression."
  {:arglists '([name docstring? attr-map? sym argtypes rettype]
               [name docstring? attr-map? sym argtypes rettype native-fn & fn-tail])}
  [name & args]
  (when (< (count args) 3)
    (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                    {:name name})))
  ;; The first non-struct vector identifies literal argtypes.
  ;; Plain forms with dynamic argtypes use the last three arguments.
  (let [anchor (first (keep-indexed (fn [i a]
                                      (when (and (vector? a)
                                                 (not (layout-vector? a)))
                                        i))
                                    args))
        [prefix sym argtypes rettype wrapper]
        (if (and anchor (pos? anchor))
          [(take (dec anchor) args)
           (nth args (dec anchor))
           (nth args anchor)
           (when (> (count args) (inc anchor)) (nth args (inc anchor)))
           (drop (+ anchor 2) args)]
          [(drop-last 3 args)
           (first (take-last 3 args))
           (second (take-last 3 args))
           (last args)
           nil])
        docstring (first (filter string? prefix))
        attr-map (first (filter map? prefix))]
    (when (nil? rettype)
      (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                      {:name name})))
    (when-not (and (<= (count prefix) 2)
                   (<= (count (filter string? prefix)) 1)
                   (<= (count (filter map? prefix)) 1)
                   (every? #(or (string? %) (map? %)) prefix))
      (throw (ex-info "babashka.ffi: defcfn accepts at most one docstring and one attribute map before the C symbol. The wrapper form needs a literal argtypes vector"
                      {:name name})))
    (when (and (seq wrapper)
               (not (and (symbol? (first wrapper)) (next wrapper))))
      (throw (ex-info "babashka.ffi: defcfn needs a raw binding name and a fn tail after the return type"
                      {:name name})))
    (when (= (first wrapper) name)
      (throw (ex-info "babashka.ffi: the raw binding name must differ from the defcfn name"
                      {:name name})))
    (let [native-fn (first wrapper)
          fn-tail (next wrapper)
          arglists (when fn-tail
                     (if (vector? (first fn-tail))
                       (list (first fn-tail))
                       (map first fn-tail)))
          name (with-meta name (cond-> (meta name)
                                 ;; :library selects the library. It is not
                                 ;; var metadata.
                                 attr-map (merge (dissoc attr-map :library))
                                 docstring (assoc :doc docstring)
                                 (and arglists
                                      (not (:arglists (meta name)))
                                      (not (:arglists attr-map)))
                                 (assoc :arglists (list 'quote arglists))))
          binding-form `(cfn ~(:library attr-map) ~sym ~argtypes ~rettype)]
      (if native-fn
        `(def ~name
           (let [~native-fn ~binding-form]
             (fn ~name ~@fn-tail)))
        `(def ~name ~binding-form)))))

;; -- manual memory ------------------------------------------------------------

(def ^:private crt-lib
  ;; Windows' default lookup does not expose the C runtime
  (delay (when (= :windows (os-key))
           (or (try (load-library "msvcrt.dll") (catch Exception _ nil))
               (try (load-library "ucrtbase.dll") (catch Exception _ nil))))))

(def ^:private c-free (delay (cfn @crt-lib "free" [:pointer] :void)))

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(declare ^:private layout-of)

(defn sizeof
  "Returns the size of a type keyword or struct layout, in bytes. The size
  of a struct includes padding."
  [t]
  (long (:size (layout-of t))))

(defn alignof
  "Returns the alignment, in bytes, of type keyword t or of a struct layout."
  [t]
  (long (:align (layout-of t))))

(defn confined-arena
  "Returns an arena for one thread.
  Create this arena in with-open to release its memory."
  ^Arena []
  (Arena/ofConfined))

(defn shared-arena
  "Returns an arena for multiple threads.
  Create this arena in with-open to release its memory."
  ^Arena []
  (Arena/ofShared))

(defn auto-arena
  "Returns an arena that the garbage collector manages.
  Keep the arena reachable while C uses its pointers. You cannot close it."
  ^Arena []
  (Arena/ofAuto))

(defn global-arena
  "Returns the global arena. Its memory exists until the process stops.
  You cannot close this arena."
  ^Arena []
  (Arena/global))

(defn- size-and-alignment
  "Returns the requested size and alignment.
  A type or layout uses natural alignment. An integer byte count uses
  alignment 16."
  [n]
  (cond (integer? n) [(long n) 16]
        (keyword? n) (let [size (long (sizeof n))] [size (max 1 (min 8 size))])
        (layout-vector? n) [(sizeof n) (alignof n)]
        :else (throw (ex-info (str "babashka.ffi: alloc takes an integer byte count, a type keyword or a layout, got " (pr-str n))
                              {:n n}))))

(defn alloc
  "Allocates zeroed native memory in arena and returns its pointer.
  n is an integer byte count, a type keyword, or a struct layout.

  Use a confined arena inside one function. Use a shared arena for memory that
  outlives the call and is released elsewhere. When the arena closes, it
  releases its memory.

  A type or layout uses natural alignment. An integer byte count uses
  alignment 16. Specify an alignment to override this value.

  There is no unscoped form. If C allocates the memory, bind its allocator with
  cfn. Release the result with the matching C deallocator.

  CAUTION: Do not close the arena while C uses its memory.
  C can access released memory."
  ([^Arena arena n]
   (let [[size align] (size-and-alignment n)]
     (alloc arena size align)))
  ([^Arena arena n alignment]
   (when-not (integer? alignment)
     (throw (ex-info (str "babashka.ffi: alloc takes an integer alignment, got " (pr-str alignment))
                     {:alignment alignment})))
   ;; Arena.allocate(byteSize) guarantees only alignment 1.
   (.allocate arena (long (first (size-and-alignment n))) (long alignment))))

(defn free
  "Releases memory that a C function returned and expects the caller to free.
  A C library that allocates usually ships its own deallocator, such as
  duckdb_free. If the library has a deallocator, use it.

  Refuses memory of a confined, shared, or automatic arena. The arena releases
  that memory. Memory of the global arena has the same scope as memory from the
  C allocator. The function cannot distinguish them. Do not pass global arena
  memory to free.

  CAUTION: Do not use p after free. This can corrupt memory or stop the process."
  [p]
  (let [seg (as-pointer p)]
    ;; memory that C handed out has the global scope; an arena gives its own
    (when-not (identical? (.scope seg) (.scope (Arena/global)))
      (throw (ex-info (str "babashka.ffi: the pointer at address " (.address seg)
                           " belongs to an arena, which releases it")
                      {:pointer seg})))
    (@c-free seg)))

(defn read
  "Reads a value of type t from p. The default byte offset is zero.

  Checks the access against the size of p. Rejects a zero-size pointer.
  reinterpret specifies a valid size."
  ([p t] (read p t 0))
  ([p t offset]
   (let [off (long offset)
         ^MemorySegment seg (accessible p)]
     (case t
       (:int :int32) (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off))
       (:uint :uint32) (bit-and (long (.get seg ValueLayout/JAVA_INT_UNALIGNED off)) 0xFFFFFFFF)
       (:long :ulong :int64 :uint64 :size_t :ssize_t)
       (.get seg ValueLayout/JAVA_LONG_UNALIGNED off)
       ;; read as a long and wrap it: the address layout's getter costs twice
       ;; as much in a native image
       :pointer (MemorySegment/ofAddress (.get seg ValueLayout/JAVA_LONG_UNALIGNED off))
       :int16 (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off))
       :uint16 (bit-and (long (.get seg ValueLayout/JAVA_SHORT_UNALIGNED off)) 0xFFFF)
       :bool (not (zero? (long (.get seg ValueLayout/JAVA_BYTE off))))
      (:int8 :byte :char) (long (.get seg ValueLayout/JAVA_BYTE off))
       :uint8 (bit-and (long (.get seg ValueLayout/JAVA_BYTE off)) 0xFF)
       :double (.get seg ValueLayout/JAVA_DOUBLE_UNALIGNED off)
       :float (.get seg ValueLayout/JAVA_FLOAT_UNALIGNED off)
       :string (string-at (.get seg ValueLayout/JAVA_LONG_UNALIGNED off))
       (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t}))))))

(defn write
  "Writes v as type t to p. The default byte offset is zero. Returns nil.

  Checks the access against the size of p. Rejects a zero-size pointer.
  reinterpret specifies a valid size."
  ([p t v] (write p t v 0))
  ([p t v offset]
   (let [off (long offset)
         ^MemorySegment seg (accessible p)]
     (case t
       (:int :uint :int32 :uint32) (.set seg ValueLayout/JAVA_INT_UNALIGNED off (unchecked-int (long v)))
       (:long :ulong :int64 :uint64 :size_t :ssize_t)
       (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (long v))
       :pointer (.set seg ValueLayout/JAVA_LONG_UNALIGNED off (long (pointer-address v)))
       (:int16 :uint16) (.set seg ValueLayout/JAVA_SHORT_UNALIGNED off (unchecked-short (long v)))
       :bool (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (if v 1 0)))
       (:int8 :uint8 :byte :char) (.set seg ValueLayout/JAVA_BYTE off (unchecked-byte (long v)))
       :double (.set seg ValueLayout/JAVA_DOUBLE_UNALIGNED off (double v))
       :float (.set seg ValueLayout/JAVA_FLOAT_UNALIGNED off (float v))
       (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t})))
     nil)))

(defn read-bytes
  "Copies n bytes from pointer p at byte offset (default 0) into a new byte
  array."
  (^bytes [p n] (read-bytes p n 0))
  (^bytes [p n offset]
   (let [n (int n)
         arr (byte-array n)
         ^MemorySegment seg (accessible p)]
     (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long offset) arr 0 n)
     arr)))

(defn write-bytes
  "Copies byte array arr into memory at pointer p at byte offset (default
  0)."
  ([p arr] (write-bytes p arr 0))
  ([p ^bytes arr offset]
   (let [n (alength arr)
         ^MemorySegment seg (accessible p)]
     (MemorySegment/copy arr 0 seg ValueLayout/JAVA_BYTE (long offset) n)
     nil)))

(defn byte-buffer
  "Returns a java.nio.ByteBuffer view of n bytes of native memory at pointer p.
  The buffer and native memory share the same bytes.

  CAUTION: Do not use the buffer after you release the native memory. An
  invalid memory access can stop the process.

  The byte order is big-endian, as it is for each new ByteBuffer. If you need a
  different byte order, set it with .order."
  ^java.nio.ByteBuffer [p n]
  (let [^MemorySegment seg (accessible p)]
    (.asByteBuffer (.asSlice seg 0 (long n)))))

(defn string->ptr
  "Copies s into arena as a NUL-terminated UTF-8 string and returns its
  pointer. The arena controls the lifetime of the string."
  ^MemorySegment [^Arena arena ^String s]
  (.allocateFrom arena s))

(def null
  "The NULL pointer."
  MemorySegment/NULL)

(defn null?
  "Returns true for a NULL pointer. Returns false for all other pointers."
  [p]
  (zero? (.address (as-pointer p))))

;; -- structs by value ---------------------------------------------------------

;; A trampoline carries one primitive per argument and cannot pass a struct
;; in registers. On AArch64, a struct larger than 16 bytes returns through x8.
;; This register is not an argument register. Libffi uses a call description
;; to put each value in the correct place. See doc/adr/ai/0003.

(defn- align-up ^long [^long n ^long a]
  (* a (quot (+ n (dec a)) a)))

;; Immutable layouts make resolved values safe to cache.
(def ^:private layout-cache (atom {}))
(def ^:private layout-cache-limit 256)

(declare ^:private layout-of*)

(defn- layout-of
  "Resolves a type keyword or struct layout. Returns a map with :type, :size,
  and :align. A struct also has :fields. Each field has a :name and :offset.
  The fields keep the order of the layout, the order of the C declaration,
  and the offsets use natural C alignment.

  A layout is a vector that starts with its kind, such as [:struct fields].
  A keyword is a primitive type."
  [t]
  (if (keyword? t)
    (layout-of* t)
    (or (get @layout-cache t)
        (let [v (layout-of* t)]
          ;; Keep the first 256 layouts. Generated layouts do not evict this set.
          (swap! layout-cache
                 (fn [m] (if (<= layout-cache-limit (count m)) m (assoc m t v))))
          v))))

(defn- layout-of*
  [t]
  (cond
    (struct-layout? t)
    (let [members (second t)]
      (when-not (= 2 (count t))
        (throw (ex-info (str "babashka.ffi: a struct layout is [:struct fields], got " (pr-str t))
                        {:layout t})))
      (when-not (and (vector? members)
                     (seq members)
                     (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %)))
                             members))
        (throw (ex-info (str "babashka.ffi: :struct needs a non-empty vector of [name type] pairs, with keyword names: "
                             (pr-str t))
                        {:layout t})))
      (when-not (apply distinct? (map first members))
        (throw (ex-info (str "babashka.ffi: a struct layout names a field twice: " (pr-str t))
                        {:layout t})))
      (let [fields (mapv (fn [[nm ty]] (assoc (layout-of ty) :name nm)) members)
            align (long (reduce max 1 (map :align fields)))
            [fields end] (reduce (fn [[fs off] f]
                                   (let [off (align-up off (:align f))]
                                     [(conj fs (assoc f :offset off))
                                      (+ off (long (:size f)))]))
                                 [[] 0] fields)]
        {:type :struct :fields fields :align align :size (align-up end align)}))

    (keyword? t)
    (if-let [size (sizes t)]
      {:type t :size size :align size}
      (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t})))

    (vector? t)
    (throw (ex-info (str "babashka.ffi: unknown layout kind " (pr-str (first t)) " in " (pr-str t))
                    {:layout t}))

    :else
    (throw (ex-info (str "babashka.ffi: not a type keyword or a layout: " (pr-str t))
                    {:layout t}))))

;; The encoder and decoder convert between Clojure values and bytes. They
;; resolve the layout when the binding is made. A call then uses the returned
;; functions without resolving the layout again.

(defn- encoder
  "Returns a function that writes a value to a segment. The function writes
  the value at offset, with layout lay. A struct value must contain each
  field and no other field. The arena contains temporary :string fields."
  [lay ^long offset]
  (let [t (:type lay)]
    (case t
      :struct
      (let [fields (:fields lay)
            c (count fields)
            names (mapv :name fields)
            ^objects encs (object-array
                           (map (fn [f] (encoder f (+ offset (long (:offset f)))))
                                fields))
            field-error
            (fn [v]
              (let [missing (when (map? v) (remove #(contains? v %) names))
                    unknown (when (map? v) (remove (set names) (keys v)))]
                (throw (ex-info (str "babashka.ffi: struct value "
                                     (cond (not (map? v)) (str "needs a map of " (pr-str names))
                                           (seq missing) (str "misses field " (pr-str (first missing)))
                                           (seq unknown) (str "has unknown field " (pr-str (first unknown)))
                                           :else (str "needs a map of " (pr-str names)))
                                     ", got " (pr-str v))
                                {:value v :fields names}))))]
        (fn [arena seg v]
          (when-not (and (map? v) (= (count v) c))
            (field-error v))
          (dotimes [i c]
            (let [x (get v (nth names i) ::missing)]
              (when (identical? ::missing x)
                (field-error v))
              ((aget encs i) arena seg x)))))
      :string (fn [arena seg v]
                (write seg :pointer
                       (if (string? v) (.allocateFrom ^Arena arena ^String v) v)
                       offset))
      ;; write :pointer takes a segment or nil itself
      (:pointer :bool) (fn [_ seg v] (write seg t v offset))
      ;; the same coercion as the FFM path: nil and a pointer become a
      ;; long, so a variadic tail value encodes like it always did
      (let [coerce (arg-coercer t)]
        (fn [_ seg v] (write seg t (coerce v) offset))))))

(defn- decoder
  "Returns a function that reads a value from a segment. The function uses
  layout lay at offset. It returns a struct as a map."
  [lay ^long offset]
  (if (= :struct (:type lay))
    (let [fields (:fields lay)
          c (count fields)
          ^objects names (object-array (map :name fields))
          ^objects decs (object-array
                         (map (fn [f] (decoder f (+ offset (long (:offset f)))))
                              fields))]
      (fn [seg]
        (let [^objects kvs (object-array (* 2 c))]
          (dotimes [i c]
            (aset kvs (* 2 i) (aget names i))
            (aset kvs (inc (* 2 i)) ((aget decs i) seg)))
          ;; The Clojure reader uses an array map for up to eight fields.
          (if (<= c 8)
            (clojure.lang.PersistentArrayMap. kvs)
            (clojure.lang.PersistentHashMap/create kvs)))))
    (let [t (:type lay)]
      (fn [seg] (read seg t offset)))))

;; libffi's FFI_TYPE_* codes, from ffi.h
(def ^:private ffi-type-codes
  {:void 0 :float 2 :double 3
   :uint8 5 :bool 5
   :int8 6 :byte 6 :char 6
   :uint16 7 :int16 8
   :uint 9 :uint32 9
   :int 10 :int32 10
   :ulong 11 :uint64 11 :size_t 11
   :long 12 :int64 12 :ssize_t 12
   :struct 13
   :pointer 14 :string 14})

;; struct ffi_type { size_t size; unsigned short alignment;
;;                   unsigned short type; struct ffi_type **elements; }
(def ^:private ffi-type-bytes 24)

;; sizeof(ffi_cif), with room for the fields that some architectures add
(def ^:private cif-bytes 256)

(defn- ffi-type!
  "Builds the ffi_type tree of layout t in arena. Returns the ffi_type."
  ^MemorySegment [^Arena arena t]
  (let [p (.allocate arena (long ffi-type-bytes) 8)]
    (if (struct-layout? t)
      (let [elems (mapv (fn [[_ ty]] (ffi-type! arena ty)) (second t))
            n (count elems)
            arr (.allocate arena (long (* 8 (inc n))) 8)]
        (dotimes [i n] (write arr :pointer (nth elems i) (* 8 i)))
        (write arr :pointer nil (* 8 n))
        ;; ffi_prep_cif fills in the size and the alignment
        (write p :size_t 0 0)
        (write p :uint16 0 8)
        (write p :uint16 (ffi-type-codes :struct) 10)
        (write p :pointer arr 16))
      (let [code (or (ffi-type-codes t)
                     (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t})))
            size (if (= :void t) 1 (long (sizeof t)))]
        (write p :size_t size 0)
        (write p :uint16 size 8)
        (write p :uint16 code 10)
        (write p :pointer nil 16)))
    p))

(defn- check-layout!
  "Compares each struct size and alignment with the ffi_prep_cif result.
  Throws an exception if they are different."
  [lay ^MemorySegment tp]
  (when (= :struct (:type lay))
    (let [size (read tp :size_t 0)
          align (read tp :uint16 8)]
      (when-not (and (= size (:size lay)) (= align (:align lay)))
        (throw (ex-info "babashka.ffi: struct layout disagrees with libffi"
                        {:babashka.ffi/layout (select-keys lay [:size :align])
                         :libffi {:size size :align align}}))))
    (let [fields (:fields lay)
          n (count fields)
          elems (reinterpret (read tp :pointer 16) (* 8 n))]
      (dotimes [i n]
        (check-layout! (nth fields i)
                       (reinterpret (read elems :pointer (* 8 i)) ffi-type-bytes))))))

;; FFI_DEFAULT_ABI comes from ffitarget.h. Read it at run time to use the
;; architecture on which the binary runs. A wrong value can corrupt memory.
;; Keep the value nil if the constant for a platform is not known.
;;
;; The Windows value assumes an MSVC-built libffi, which is what
;; script/setup-libffi.bat installs through vcpkg: FFI_WIN64 is 1 and
;; FFI_GNUW64, the value a MinGW build defaults to, is 2. The two differ in
;; the width of long double, which babashka.ffi has no type for.
(def ^:private default-abi
  (delay
    (let [arch (System/getProperty "os.arch")
          amd64? (contains? #{"amd64" "x86_64"} arch)]
      (cond (= :windows (os-key)) (when amd64? 1)   ; FFI_WIN64
            (= "aarch64" arch) 1                    ; FFI_SYSV
            amd64? 2                                ; FFI_UNIX64
            :else nil))))

(def ^:private linked-libffi
  "The libffi that is linked into a native image. Calls use the @CFunction
  bindings in babashka.impl.libffi. The value is resolved only when the build
  links the archive. See BABASHKA_FEATURE_LIBFFI and script/libffi_archive.sh.
  The value is nil on the JVM."
  (when (and native-image? (= "true" (System/getenv "BABASHKA_FEATURE_LIBFFI")))
    (try {:prep-cif @(requiring-resolve 'babashka.impl.libffi/prep-cif)
          :prep-cif-var @(requiring-resolve 'babashka.impl.libffi/prep-cif-var)
          :call @(requiring-resolve 'babashka.impl.libffi/call)}
         (catch Throwable _ nil))))

(def ^:private libffi
  "The ffi_prep_cif and ffi_call functions, which accept addresses. A native
  image uses its linked libffi. On the JVM, the functions use the system
  libffi."
  (delay
    (let [entry (or linked-libffi
                    (when-not native-image?
                      (try (load-system-library "ffi") (catch Exception _ nil))
                      (let [prep (find-symbol "ffi_prep_cif")
                            prep-var (find-symbol "ffi_prep_cif_var")
                            call (find-symbol "ffi_call")]
                        (when (and prep prep-var call)
                          ;; addresses travel as longs: the same carrier as
                          ;; a pointer, without the pointer checks
                          {:prep-cif (cfn prep [:long :int :uint :long :long] :int)
                           :prep-cif-var (cfn prep-var [:long :int :uint :uint :long :long] :int)
                           :call (cfn call [:long :long :long :long] :void)}))))]
      (when-not entry
        (throw (ex-info (if native-image?
                          "babashka.ffi: this call needs libffi, and this babashka binary was built without it (see bb describe, :libffi/version)"
                          "babashka.ffi: this call needs libffi, which is not installed on this system")
                        {})))
      (when-not @default-abi
        (throw (ex-info (str "babashka.ffi: libffi calls are not supported on "
                             (System/getProperty "os.name") " "
                             (System/getProperty "os.arch"))
                        {})))
      entry)))

(defn- libffi-available?
  "True when this process can make libffi calls: the linked libffi in a
  native image, the system libffi on the JVM."
  []
  (try @libffi true (catch Exception _ false)))

(defn- libffi-cfn
  "Returns a libffi binding: a struct signature on any platform, and in a
  native image every fixed signature without a trampoline and every variadic
  call. Builds the cif and ffi_type trees once, in an arena that the
  garbage collector releases when the binding becomes unreachable. Each
  call uses one allocation for its temporary values. nfixed, for a variadic call,
  is the number of declared parameters."
  ([lib sym argtypes rettype] (libffi-cfn lib sym argtypes rettype nil nil))
  ([lib sym argtypes rettype nfixed] (libffi-cfn lib sym argtypes rettype nfixed nil))
  ([lib sym argtypes rettype nfixed fnp0]
  (let [n (count argtypes)
        void? (= :void rettype)
        ;; the layouts resolve first, so that a bad one is an error even
        ;; where there is no libffi
        alays (mapv layout-of argtypes)
        rlay (if void? {:type :void :size 8 :align 8} (layout-of rettype))
        {:keys [prep-cif prep-cif-var call]} @libffi
        ;; the garbage collector releases this arena when the binding
        ;; becomes unreachable: the call below holds the cif segment, and a
        ;; segment keeps its arena reachable
        arena (Arena/ofAuto)
        atypes (mapv #(ffi-type! arena %) argtypes)
        rtype (ffi-type! arena rettype)
        atypes-arr (.allocate arena (long (* 8 (max 1 n))) 8)
        ^MemorySegment cif (.allocate arena (long cif-bytes) 16)
        cif-addr (.address cif)]
    (dotimes [i n] (write atypes-arr :pointer (nth atypes i) (* 8 i)))
    (let [status (long (if nfixed
                         (prep-cif-var cif-addr @default-abi (long nfixed) n
                                       (.address rtype) (.address atypes-arr))
                         (prep-cif cif-addr @default-abi n (.address rtype) (.address atypes-arr))))]
      (when-not (zero? status)
        (throw (ex-info (str "babashka.ffi: ffi_prep_cif failed for " sym)
                        {:symbol sym :status status}))))
    (dotimes [i n] (check-layout! (nth alays i) (nth atypes i)))
    (when-not void? (check-layout! rlay rtype))
    (let [slot-size (fn ^long [^long s] (align-up (max 8 s) 8))
          slot-sizes (mapv #(slot-size (long (:size %))) alays)
          rvalue-off (* 8 (max 1 n))
          ;; libffi widens an integer return to ffi_arg, so the return slot
          ;; is never smaller than a word
          base-off (+ rvalue-off (slot-size (long (:size rlay))))
          slot-offs (long-array (butlast (reductions + base-off slot-sizes)))
          total (long (reduce + base-off slot-sizes))
          ^objects encs (object-array (map-indexed (fn [i lay] (encoder lay (aget slot-offs i)))
                                                   alays))
          decode (when-not void? (decoder rlay rvalue-off))
          ;; a variadic binding shares one address delay over its tail
          ;; shapes, so a :library function is asked once per binding
          fnp (or fnp0 (delay (.address (require-symbol lib sym))))
          arity-error (fn [got]
                        (throw (ex-info (str "babashka.ffi: " sym " expects " n
                                             " args, got " got)
                                        {:symbol sym})))]
      (with-meta
        ;; Each call uses a confined arena for scratch memory. Thus, threads
        ;; can share a binding and a call can re-enter it.
        (fn [& args]
          (let [args (vec args)]
            (when-not (= n (count args)) (arity-error (count args)))
            (with-open [a (Arena/ofConfined)]
              (let [scratch (.allocate a total 16)
                    base (.address scratch)]
                (dotimes [i n]
                  (write scratch :long (+ base (aget slot-offs i)) (* 8 i))
                  ((aget encs i) a scratch (nth args i)))
                ;; the fence keeps the cif segment, and with it the arena,
                ;; reachable while libffi reads them: without it the VM may
                ;; collect the binding during its own call
                (try (call (.address cif) @fnp (+ base rvalue-off) base)
                     (finally (java.lang.ref.Reference/reachabilityFence cif)))
                (when decode (decode scratch))))))
        {:babashka.ffi/backend :libffi})))))

;; -- callbacks ----------------------------------------------------------------

(def ^:private callback-arenas (atom {}))

(defn callback
  "Creates a C function pointer that invokes f. argtypes and rettype use the
  cfn type keywords. f receives :pointer arguments as zero-size pointers.
  It receives :bool arguments as booleans and other arguments as longs or doubles.

  Returns the function pointer. The callback remains valid until
  free-callback releases it."
  [f argtypes rettype]
  (doseq [t argtypes] (carrier t))
  (carrier rettype)
  (when (some #(= :void %) argtypes)
    (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                    {:argtypes argtypes})))
  (when (and native-image?
             (or (> (count argtypes) 4)
                 (some #(= :float (carrier %)) argtypes)
                 (> (count (filter #(= :double (carrier %)) argtypes)) 2)
                 (= :float (carrier rettype))))
    (throw (unsupported-ex "callback" argtypes rettype
                           "callbacks support up to 4 args, at most 2 :double, no :float, and a :void, integer or :double return")))
  (let [;; f returns arbitrary Clojure values and receives raw carriers:
        ;; coerce the result to the declared return type (a Boolean or
        ;; Integer crossing the upcall boundary uncaught would kill the VM)
        ;; and hand f the declared types, not the carriers
        ret-c (when-not (= :void rettype) (arg-coercer rettype))
        in-c (mapv (fn [t] (case t
                             :bool (fn [a] (not (zero? (long a))))
                             :pointer (fn [a] (MemorySegment/ofAddress (long a)))
                             nil))
                   argtypes)
        f (if (or ret-c (some some? in-c))
            (let [g f]
              (fn [& args]
                (let [r (apply g (map-indexed
                                  (fn [i a]
                                    (if-let [c (nth in-c i)] (c a) a))
                                  args))]
                  (if ret-c (ret-c r) r))))
            f)
        n (count argtypes)
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
                          (make-array java.lang.foreign.Linker$Option 0))]
    (swap! callback-arenas assoc (.address stub) arena)
    stub))

(defn free-callback
  "Releases callback pointer p. C must not call p after this function returns.
  Ignores unknown and previously freed pointers."
  [p]
  (let [addr (if (instance? MemorySegment p)
               ;; A freed stub has a closed arena but keeps its address.
               (.address ^MemorySegment p)
               (pointer-address p))]
    (when-let [^Arena a (get @callback-arenas addr)]
      (swap! callback-arenas dissoc addr)
      (.close a)))
  nil)
