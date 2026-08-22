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
  return the truthy number 0. The API does not support struct-by-value
  arguments.

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
  (let [lib (if (map? lib) (:lookup lib) lib)
        lookups (if lib [lib] (conj @libraries (.defaultLookup ^Linker @linker*)))]
    (some (fn [^SymbolLookup l] (.orElse (.find l sym) nil)) lookups)))

(defn- require-symbol ^MemorySegment [lib ^String sym]
  (or (lookup-symbol lib sym)
      (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym}))))

(defn find-symbol
  "Finds sym in the loaded libraries and the default system lookup. Returns
  its native address as a Clojure long. Returns nil for an unknown symbol."
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
  "Creates a Clojure function that calls C function sym. argtypes is a vector
  of type keywords. rettype is a type keyword.

  With a library map, cfn searches only that library. Without one, cfn
  searches all loaded libraries and then the default system lookup. The first
  call resolves the symbol and creates the call handle. You can create the
  binding before you load its library.

  A trailing :& declares a variadic C function. The types before :& are the
  fixed parameters. Each call infers the tail types from its values."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when-not (string? sym)
     (throw (ex-info (str "babashka.ffi: C symbol must be a string: " (pr-str sym))
                     {:sym sym})))
   (when (some #(= :void %) argtypes)
     (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                     {:argtypes argtypes})))
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
  preserves all metadata on name. This metadata includes ^:private."
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
                             attr-map (merge attr-map)
                             docstring (assoc :doc docstring)))
       (cfn ~sym ~argtypes ~rettype))))

;; -- manual memory ------------------------------------------------------------

(def ^:private crt-lib
  ;; Windows' default lookup does not expose the C runtime
  (delay (when (= :windows (os-key))
           (or (try (load-library "msvcrt.dll") (catch Exception _ nil))
               (try (load-library "ucrtbase.dll") (catch Exception _ nil))))))

(def ^:private c-calloc (delay (cfn @crt-lib "calloc" [:size_t :size_t] :pointer)))
(def ^:private c-free (delay (cfn @crt-lib "free" [:pointer] :void)))

(defn alloc
  "Allocates n bytes of zeroed native memory and returns its pointer. Release
  the pointer with free."
  [n]
  (@c-calloc 1 n))

(defn free
  "Releases memory allocated by alloc or string->ptr."
  [p]
  (@c-free p))

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(defn sizeof
  "Returns the size, in bytes, of type keyword t."
  [t]
  (or (sizes t) (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t}))))

(defn read
  "Reads a value of type t from pointer p. The optional byte offset defaults
  to 0."
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
  "Writes value v as type t to pointer p. The optional byte offset defaults
  to 0. Returns nil."
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
