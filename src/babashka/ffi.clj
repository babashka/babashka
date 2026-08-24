(ns babashka.ffi
  "Call functions in native shared libraries.

  Load a library, bind C functions with explicit argument and return types,
  and manage native memory:

      (require '[babashka.ffi :as ffi])
      (ffi/load-system-library \"sqlite3\")
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try (sqlite3-open \"x.db\" pp)
             (ffi/read pp :pointer)
             (finally (ffi/free pp))))

  Use these type keywords:

      :void
      :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
      :uint32 :int64 :uint64 :size_t :ssize_t :char :byte
      :bool :pointer :string :double :float

  Pointers are native addresses stored in Clojure longs. :bool represents a
  one-byte C boolean and returns true or false. Thus, a C predicate does not
  return the truthy number 0.

  Write a struct that C passes or returns by value as {:struct [layouts]}.
  Struct layouts nest, and their values are vectors of fields:

      (ffi/defcfn div \"div\" [:int :int] {:struct [:int :int]})
      (div 7 2)                      ; [3 1]

  These calls go through libffi. See doc/ffi.md.

  Native images limit most fixed signatures to six arguments. A signature
  that uses only pointer and integer types supports up to 10 arguments. A
  fixed signature supports at most three mixed floating-point arguments. It
  supports four arguments of the same floating-point type. A :float return
  supports at most four arguments.

  In native images, variadic calls support up to five total arguments. They
  support at most three fixed arguments and two :double arguments. Callbacks
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
  "Reads the NUL-terminated UTF-8 string at pointer p. Returns nil for a NULL
  pointer."
  [p]
  (when-not (zero? (long p))
    (.getString (segment p Long/MAX_VALUE) 0)))

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
        as-bool (fn [a] (if a 1 0))]
    (into {:double double :float float :bool as-bool}
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

(defn- lookup-symbol ^MemorySegment [lib ^String sym]
  (let [;; a delay or a var, so that a binding can name a library that
        ;; another thing loads later
        lib (if (instance? clojure.lang.IDeref lib) @lib lib)
        lib (if (map? lib) (:lookup lib) lib)
        lookups (if lib [lib] (conj @libraries (.defaultLookup ^Linker @linker*)))]
    (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)))

(defn- require-symbol ^MemorySegment [lib sym]
  (if (integer? sym)
    ;; a function pointer: the caller resolved it already
    (MemorySegment/ofAddress (long sym))
    (or (lookup-symbol lib ^String sym)
        (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym})))))

(defn find-symbol
  "Finds sym and returns its native address as a Clojure long. Returns nil
  for an unknown symbol.

  With a library map, find-symbol searches that library and the libraries
  that it links. Without one, it searches all loaded libraries and then the
  default system lookup."
  ([sym] (find-symbol nil sym))
  ([lib sym]
   (some-> (lookup-symbol lib (str sym)) .address)))

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

(declare ^:private fixed-cfn struct-cfn struct-layout?)

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
  "Creates a Clojure function that calls C function sym. sym is the name of a
  C symbol, or the address of a function as a Clojure long. argtypes is a
  vector of type keywords. rettype is a type keyword. A struct that C passes
  or returns by value is a layout, {:struct [layouts]}, and its values are
  vectors of fields. Such a call goes through libffi.

  An address binds a function that has no name to look up: a pointer from a
  loader such as glXGetProcAddress, a pointer that a C function returns, a
  pointer in a struct, or the result of callback. find-symbol and callback
  both return such an address.

  With a library map, cfn searches that library and the libraries that it
  links, so a symbol that the library defines resolves to the definition in
  that library. Without one, cfn
  searches all loaded libraries and then the default system lookup. The first
  call resolves the symbol and creates the call handle. You can create the
  binding before you load its library.

  A trailing :& declares a variadic C function. The types before :& are the
  fixed parameters. Each call infers the tail types from its values."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when-not (or (string? sym) (integer? sym))
     (throw (ex-info (str "babashka.ffi: C symbol must be a string or an address: "
                          (pr-str sym))
                     {:sym sym})))
   ;; a null function pointer crashes the process on the first call, and it
   ;; is what a loader returns for a function that it does not have
   (when (and (integer? sym) (zero? sym))
     (throw (ex-info "babashka.ffi: cannot bind the null address" {:sym sym})))
   (when (some #(= :void %) argtypes)
     (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                     {:argtypes argtypes})))
   (let [fixed (check-variadic-marker argtypes)
         structs? (or (struct-layout? rettype) (boolean (some struct-layout? argtypes)))]
     (cond
       (and structs? fixed)
       (throw (ex-info (str "babashka.ffi: a variadic signature cannot pass a struct by value: " sym)
                       {:symbol sym :argtypes argtypes :rettype rettype}))
       structs? (struct-cfn lib sym argtypes rettype)
       fixed (variadic-cfn lib sym fixed argtypes rettype)
       :else (fixed-cfn lib sym argtypes rettype)))))

(defn- fixed-cfn
  [lib sym argtypes rettype]
  (let [types argtypes
        perm (sort-permutation types)
        types* (if perm (mapv types perm) types)
        ;; raw invoker: a fn of the coerced argument array. In a native
        ;; image a generated trampoline (compiled direct call) when the
        ;; shape has one; otherwise an FFM downcall handle.
        tramp-id (get trampoline-ids (shape-key types* rettype))
        ;; in a native image every supported shape is known ahead of time
        ;; (ordered shapes on Windows, canonical elsewhere), so reject
        ;; unsupported signatures here with a useful message instead of
        ;; GraalVM's rebuild-the-image error at call time
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
  "Defines name as a C function binding created by cfn:

      (defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int)

      (defcfn sqlite3-open
        \"Opens the database at path, storing the handle in out-param pp.\"
        \"sqlite3_open\" [:string :pointer] :int)

  An optional docstring and attribute map can precede the C symbol. The final
  three arguments are the C symbol, argument types, and return type. defcfn
  preserves all metadata on name. This metadata includes ^:private.

  The attribute map key :library gives cfn a library to search, as a library
  map or as something that derefs to one:

      (def sqlite (delay (ffi/load-library (extract-bundled-library!))))
      (defcfn sqlite3-open {:library sqlite} \"sqlite3_open\"
        [:string :pointer] :int)

  A binding without :library searches all loaded libraries and then the
  default system lookup, where a library of the same name that the system
  installs can supply the symbol instead."
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
                   (<= (count (filter map? prefix)) 1)
                   (every? #(or (string? %) (map? %)) prefix))
      (throw (ex-info "babashka.ffi: defcfn takes at most a docstring and an attribute map before the C symbol"
                      {:name name})))
    `(def ~(with-meta name (cond-> (meta name)
                             ;; :library selects the library, it is not
                             ;; metadata about the var
                             attr-map (merge (dissoc attr-map :library))
                             docstring (assoc :doc docstring)))
       (cfn ~(:library attr-map) ~sym ~argtypes ~rettype))))

;; -- manual memory ------------------------------------------------------------

(def ^:private crt-lib
  ;; Windows' default lookup does not expose the C runtime
  (delay (when (= :windows (os-key))
           (or (try (load-library "msvcrt.dll") (catch Exception _ nil))
               (try (load-library "ucrtbase.dll") (catch Exception _ nil))))))

(def ^:private c-calloc (delay (cfn @crt-lib "calloc" [:size_t :size_t] :pointer)))
(def ^:private c-free (delay (cfn @crt-lib "free" [:pointer] :void)))

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(declare layout-of)

(defn sizeof
  "Returns the size, in bytes, of type keyword t, or of a struct layout
  {:struct [layouts]}. A struct layout has the size that a C compiler gives
  it, padding included."
  [t]
  (or (sizes t)
      (:size (layout-of t))))

(defn confined-arena
  "An arena for this thread only. Allocation in it is cheap, like a native
  stack. Closing it releases everything allocated in it, so create it in a
  with-open clause."
  ^Arena []
  (Arena/ofConfined))

(defn shared-arena
  "An arena that threads share. Closing it from any thread releases
  everything allocated in it."
  ^Arena []
  (Arena/ofShared))

(defn auto-arena
  "An arena that the garbage collector manages. It releases everything
  allocated in it once nothing refers to the arena. It cannot be closed."
  ^Arena []
  (Arena/ofAuto))

(defn global-arena
  "The arena that never closes. Memory allocated in it lives as long as the
  process."
  ^Arena []
  (Arena/global))

(defn alloc
  "Allocates n bytes of zeroed native memory and returns its pointer.

  With an arena, closing the arena releases the memory. Without one, the
  memory comes from the C allocator and free releases it. Do not call free
  on a pointer that an arena allocated.

  n is a byte count, a type keyword, or a struct layout."
  ([n] (@c-calloc 1 (if (number? n) n (sizeof n))))
  ([^Arena arena n]
   (.address (.allocate arena (long (if (number? n) n (sizeof n)))))))

(defn free
  "Releases memory allocated by alloc or string->ptr."
  [p]
  (@c-free p))

;; the segment arities carry the struct path, which reads and writes many
;; fields of one already-materialized segment
(defn- read-seg
  [^MemorySegment seg t ^long off]
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
    (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t}))))

(defn read
  "Reads a value of type t from pointer p. The optional byte offset defaults
  to 0."
  ([p t] (read p t 0))
  ([p t offset]
   (read-seg (segment p (+ (long offset) (long (sizeof t)))) t (long offset))))

(defn- write-seg
  [^MemorySegment seg t ^long off v]
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
  nil)

(defn write
  "Writes value v as type t to pointer p. The optional byte offset defaults
  to 0. Returns nil."
  ([p t v] (write p t 0 v))
  ([p t offset v]
   (write-seg (segment p (+ (long offset) (long (sizeof t)))) t (long offset) v)))

(defn read-bytes
  "Copies n bytes from pointer p at byte offset (default 0) into a new byte
  array."
  (^bytes [p n] (read-bytes p n 0))
  (^bytes [p n offset]
   (let [n (int n)
         arr (byte-array n)]
     (MemorySegment/copy (segment p (+ (long offset) n)) ValueLayout/JAVA_BYTE
                         (long offset) arr 0 n)
     arr)))

(defn write-bytes
  "Copies byte array arr into memory at pointer p at byte offset (default
  0)."
  ([p arr] (write-bytes p arr 0))
  ([p ^bytes arr offset]
   (let [n (alength arr)]
     (MemorySegment/copy arr 0 (segment p (+ (long offset) n))
                         ValueLayout/JAVA_BYTE (long offset) n)
     nil)))

(defn byte-buffer
  "A java.nio.ByteBuffer view over n bytes of native memory at pointer p:
  reads and writes go straight to the native memory, nothing is copied.
  The buffer is only valid while the memory is. Byte order is big-endian,
  as for any new ByteBuffer; set it with .order if needed."
  ^java.nio.ByteBuffer [p n]
  (.asByteBuffer (segment p n)))

(defn string->ptr
  "Copies s to newly allocated native memory as a NUL-terminated UTF-8
  string. Returns its pointer. Release the pointer with free."
  [^String s]
  (let [bytes (.getBytes s "UTF-8")
        n (inc (alength bytes))
        p (alloc n)
        seg (segment p n)]
    (MemorySegment/copy bytes 0 seg ValueLayout/JAVA_BYTE 0 (alength bytes))
    p))

(def null
  "The NULL pointer address."
  0)

(defn null?
  "Returns true for a NULL pointer. Returns false for all other pointers."
  [p]
  (zero? (long p)))

;; -- callbacks ----------------------------------------------------------------

(def ^:private callback-arenas (atom {}))

(defn callback
  "Creates a C function pointer that invokes Clojure function f. argtypes and
  rettype use the same type keywords as cfn. Pointer and integer arguments
  are Clojure longs. :bool arguments are booleans.

  Returns the function pointer as a Clojure long. The callback remains valid
  until free-callback releases it."
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
        ;; and give :bool arguments to f as booleans
        ret-c (when-not (= :void rettype) (arg-coercer rettype))
        bool-args (mapv #(= :bool %) argtypes)
        f (if (or ret-c (some true? bool-args))
            (let [g f]
              (fn [& args]
                (let [r (apply g (map-indexed
                                  (fn [i a]
                                    (if (nth bool-args i) (not (zero? (long a))) a))
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
                          (make-array java.lang.foreign.Linker$Option 0))
        addr (.address stub)]
    (swap! callback-arenas assoc addr arena)
    addr))

(defn free-callback
  "Releases callback pointer p. C must not call p after this function returns.
  Ignores unknown pointers."
  [p]
  (when-let [^Arena a (get @callback-arenas p)]
    (swap! callback-arenas dissoc p)
    (.close a))
  nil)

;; -- structs by value ---------------------------------------------------------

;; A trampoline carries one primitive per argument, so it cannot pass a
;; struct in registers, and on AArch64 a struct larger than 16 bytes comes
;; back through x8, which is not an argument register. libffi places the
;; arguments itself, from a description of the call, so a binding that has a
;; struct in it calls through libffi.

(defn- struct-layout? [t]
  (and (map? t) (contains? t :struct)))

(defn- align-up ^long [^long n ^long a]
  (* a (quot (+ n (dec a)) a)))

(defn- layout-of
  "Resolves layout t to a map of :type, :size and :align. A struct layout
  also has :fields, each a layout with an :offset. Offsets follow natural
  alignment, as a C compiler lays a struct out."
  [t]
  (cond
    (struct-layout? t)
    (let [members (:struct t)]
      (when-not (and (sequential? members) (seq members))
        (throw (ex-info (str "babashka.ffi: :struct needs a non-empty vector of layouts: "
                             (pr-str t))
                        {:layout t})))
      (let [fields (mapv layout-of members)
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

    :else
    (throw (ex-info (str "babashka.ffi: not a type or a struct layout: " (pr-str t))
                    {:layout t}))))

;; encoder and decoder are the only places that turn Clojure values into
;; bytes and back. Both resolve the layout once, when the binding is made,
;; and hand back a function that a call runs without looking at the layout
;; again.

(defn- encoder
  "A function of an arena, a segment and a value, that writes the value at
  offset as layout lay. A struct value is a vector of its fields. The arena
  backs the C string of a :string field and must outlive the call."
  [lay ^long offset]
  (let [t (:type lay)]
    (case t
      :struct
      (let [fields (:fields lay)
            c (count fields)
            ^objects encs (object-array
                           (map (fn [f] (encoder f (+ offset (long (:offset f)))))
                                fields))]
        (fn [arena seg v]
          (when-not (and (sequential? v) (= (count v) c))
            (throw (ex-info (str "babashka.ffi: a struct value needs " c
                                 " fields, got " (pr-str v))
                            {:value v :fields c})))
          (dotimes [i c]
            ((aget encs i) arena seg (nth v i)))))
      :bool (fn [_ seg v] (write-seg seg :bool offset v))
      :string (fn [arena seg v]
                (write-seg seg :pointer offset
                           (if (string? v)
                             (.address (.allocateFrom ^Arena arena ^String v))
                             ((arg-coercer :pointer) v))))
      (let [coerce (arg-coercer t)]
        (fn [_ seg v] (write-seg seg t offset (coerce v)))))))

(defn- decoder
  "A function of a segment that reads a value of layout lay at offset. A
  struct becomes a vector of its fields."
  [lay ^long offset]
  (if (= :struct (:type lay))
    (let [^objects decs (object-array
                         (map (fn [f] (decoder f (+ offset (long (:offset f)))))
                              (:fields lay)))
          c (alength decs)]
      (fn [seg]
        (loop [i 0 acc (transient [])]
          (if (< i c)
            (recur (inc i) (conj! acc ((aget decs i) seg)))
            (persistent! acc)))))
    (let [t (:type lay)]
      (fn [seg] (read-seg seg t offset)))))

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
  "Builds the ffi_type tree of layout t in arena. Returns its address."
  ^long [^Arena arena t]
  (let [p (.allocate arena (long ffi-type-bytes) 8)]
    (if (struct-layout? t)
      (let [elems (mapv #(ffi-type! arena %) (:struct t))
            n (count elems)
            arr (.allocate arena (long (* 8 (inc n))) 8)]
        (dotimes [i n] (write-seg arr :pointer (* 8 i) (nth elems i)))
        (write-seg arr :pointer (* 8 n) 0)
        ;; ffi_prep_cif fills in the size and the alignment
        (write-seg p :size_t 0 0)
        (write-seg p :uint16 8 0)
        (write-seg p :uint16 10 (ffi-type-codes :struct))
        (write-seg p :pointer 16 (.address arr)))
      (let [code (or (ffi-type-codes t)
                     (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t})))
            size (if (= :void t) 1 (long (sizeof t)))]
        (write-seg p :size_t 0 size)
        (write-seg p :uint16 8 size)
        (write-seg p :uint16 10 code)
        (write-seg p :pointer 16 0)))
    (.address p)))

(defn- check-layout!
  "Compares the size and the alignment of every struct in a layout with what
  ffi_prep_cif computed for it."
  [lay tp]
  (when (= :struct (:type lay))
    (let [size (read tp :size_t 0)
          align (read tp :uint16 8)]
      (when-not (and (= size (:size lay)) (= align (:align lay)))
        (throw (ex-info "babashka.ffi: struct layout disagrees with libffi"
                        {:babashka.ffi/layout (select-keys lay [:size :align])
                         :libffi {:size size :align align}}))))
    (let [elems (read tp :pointer 16)
          fields (:fields lay)]
      (dotimes [i (count fields)]
        (check-layout! (nth fields i) (read elems :pointer (* 8 i)))))))

;; FFI_DEFAULT_ABI, from ffitarget.h. Read at run time, not when the image
;; is built, so that the architecture is the one the binary runs on.
(def ^:private default-abi
  (delay
    (let [arch (System/getProperty "os.arch")]
      (cond (= :windows (os-key)) nil
            (= "aarch64" arch) 1                    ; FFI_SYSV
            (contains? #{"amd64" "x86_64"} arch) 2  ; FFI_UNIX64
            :else nil))))

(def ^:private linked-libffi
  "The libffi of a native image built with BABASHKA_LIBFFI, called through
  @CFunction bindings that the linker resolved in the archive. nil on the
  JVM and in a native image built without it, where the namespace holding
  those bindings is not on the classpath."
  (when native-image?
    (try {:prep-cif @(requiring-resolve 'babashka.impl.libffi/prep-cif)
          :call @(requiring-resolve 'babashka.impl.libffi/call)}
         (catch Throwable _ nil))))

(def ^:private libffi
  "ffi_prep_cif and ffi_call. A native image built with BABASHKA_LIBFFI has
  libffi linked in. On the JVM they come from the system libffi."
  (delay
    (let [entry (or linked-libffi
                    ;; only on the JVM: a native image carries its own
                    ;; libffi or refuses, so that a struct binding does not
                    ;; depend on what the machine happens to have installed
                    (when-not native-image?
                      (try (load-system-library "ffi") (catch Exception _ nil))
                      (let [prep (find-symbol "ffi_prep_cif")
                            call (find-symbol "ffi_call")]
                        (when (and prep call)
                          {:prep-cif (cfn prep [:pointer :int :uint :pointer :pointer] :int)
                           :call (cfn call [:pointer :pointer :pointer :pointer] :void)}))))]
      (when-not entry
        (throw (ex-info (if native-image?
                          "babashka.ffi: passing a struct by value needs libffi, and this babashka binary was built without it"
                          "babashka.ffi: passing a struct by value needs libffi, which is not installed on this system")
                        {})))
      (when-not @default-abi
        (throw (ex-info (str "babashka.ffi: passing a struct by value is not supported on "
                             (System/getProperty "os.name") " "
                             (System/getProperty "os.arch"))
                        {})))
      entry)))

(defn- struct-cfn
  "A binding that passes or returns a struct by value, through libffi. The
  cif and the ffi_type trees are built once, in the global arena. Every call
  needs scratch memory for the argument slots, the argument pointer array
  and the return value, which one allocation covers."
  [lib sym argtypes rettype]
  (let [n (count argtypes)
        void? (= :void rettype)
        ;; the layouts resolve first, so that a bad one is an error even
        ;; where there is no libffi
        alays (mapv layout-of argtypes)
        rlay (if void? {:type :void :size 8 :align 8} (layout-of rettype))
        {:keys [prep-cif call]} @libffi
        arena (Arena/global)
        atype-ptrs (mapv #(ffi-type! arena %) argtypes)
        rtype-ptr (ffi-type! arena rettype)
        atypes-arr (.allocate arena (long (* 8 (max 1 n))) 8)
        cif (.allocate arena (long cif-bytes) 16)
        cif-addr (.address cif)]
    (dotimes [i n] (write-seg atypes-arr :pointer (* 8 i) (nth atype-ptrs i)))
    (let [status (long (prep-cif cif-addr @default-abi n rtype-ptr (.address atypes-arr)))]
      (when-not (zero? status)
        (throw (ex-info (str "babashka.ffi: ffi_prep_cif failed for " sym)
                        {:symbol sym :status status}))))
    (dotimes [i n] (check-layout! (nth alays i) (nth atype-ptrs i)))
    (when-not void? (check-layout! rlay rtype-ptr))
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
          fnp (delay (.address (require-symbol lib sym)))
          arity-error (fn [got]
                        (throw (ex-info (str "babashka.ffi: " sym " expects " n
                                             " args, got " got)
                                        {:symbol sym})))]
      (with-meta
        ;; scratch comes from a confined arena per call, which costs about
        ;; 50ns and is correct when threads share the binding and when a
        ;; call re-enters it
        (fn [& args]
          (let [args (vec args)]
            (when-not (= n (count args)) (arity-error (count args)))
            (with-open [a (Arena/ofConfined)]
              (let [scratch (.allocate a total 16)
                    base (.address scratch)]
                (dotimes [i n]
                  (write-seg scratch :pointer (* 8 i) (+ base (aget slot-offs i)))
                  ((aget encs i) a scratch (nth args i)))
                (call cif-addr @fnp (+ base rvalue-off) base)
                (when decode (decode scratch))))))
        {:babashka.ffi/backend :libffi}))))
