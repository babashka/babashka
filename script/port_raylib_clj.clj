;; Ports the b12n-raylib-clj binding layer (coffi) to babashka.ffi.
;;
;;   bb script/port_raylib_clj.clj [src-root] [out-root]
;;
;; Both projects spell a binding the same way, so this is a type translation:
;; every (defcfn name doc? attrs? "CSymbol" [args] ret) is rewritten with
;; babashka.ffi types. Colors become a packed :uint with a wrapper that still
;; accepts {:r :g :b :a}. A signature that needs struct-by-value becomes a
;; function that throws, so a namespace still loads and only the calls that
;; cannot work fail.
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n])

(def src-root (or (first *command-line-args*)
                  (str (fs/home) "/dev/b12n-raylib-clj/src")))
(def out-root (or (second *command-line-args*) "port"))

;; coffi type -> babashka.ffi type
(def type-map
  {:mem/void :void :mem/int :int :mem/long :long :mem/short :int16
   :mem/byte :int8 :mem/char :char :mem/float :float :mem/double :double
   :mem/pointer :pointer :mem/c-string :string :mem/size-t :size_t
   :mem/segment :pointer :mem/short-array :pointer :mem/int-array :pointer
   :ri/bool :bool :ri/ubyte :uint8 :ri/uint :uint :ri/ulong :uint64})

(def color-type :rs/color)

;; rewrite-clj renders an auto-resolved ::mem/int as :??_mem_??/int
(defn kw [s]
  (-> (str s)
      (str/replace #"^:+" "")
      (str/replace #"\?\?_|_\?\?" "")
      keyword))

(defn translate-type [t]
  (let [k (kw t)]
    (cond
      (= color-type k) :uint                      ; packed r|g<<8|b<<16|a<<24
      (contains? type-map k) (type-map k)
      :else nil)))                                ; struct: unsupported

(defn form->binding
  "Pulls [name doc attrs sym args ret] out of a defcfn form node."
  [node]
  (let [children (remove #(#{:whitespace :newline :comma} (n/tag %))
                         (n/children node))
        forms (map n/sexpr (rest children))       ; drop the defcfn symbol
        nm (first forms)
        rst (rest forms)
        doc (when (string? (first rst)) (first rst))
        rst (if doc (rest rst) rst)
        attrs (when (map? (first rst)) (first rst))
        rst (if attrs (rest rst) rst)
        [sym args ret] rst]
    (when (and (symbol? nm) (string? sym) (vector? args))
      {:name nm :doc doc :attrs attrs :sym sym :args args :ret ret})))

(defn emit-binding [{:keys [name doc attrs sym args ret]}]
  (let [targs (map translate-type args)
        tret (translate-type ret)
        colors (keep-indexed (fn [i a] (when (= color-type (kw a)) i)) args)]
    (cond
      (or (some nil? targs) (nil? tret))
      ;; needs struct-by-value: keep the name, fail only if called
      (format "(defn %s [& _]\n  (throw (ex-info \"%s needs struct-by-value, not yet supported by babashka.ffi\" {:symbol \"%s\"})))"
              name sym sym)

      (seq colors)
      ;; raw binding plus a wrapper that still takes {:r :g :b :a}
      (let [params (map-indexed (fn [i _] (symbol (str "a" i))) args)
            wrapped (map-indexed (fn [i p] (if (some #{i} colors) (list 'pack-color p) p))
                                 params)]
        (format "(defcfn %s-raw%s \"%s\" %s %s)\n\n(defn %s%s [%s]\n  (%s-raw %s))"
                name (if doc (str "\n  " (pr-str doc)) "") sym (pr-str (vec targs)) tret
                name (if doc (str "\n  " (pr-str doc)) "")
                (str/join " " params) name (str/join " " wrapped)))

      :else
      (format "(defcfn %s%s \"%s\" %s %s)"
              name (if doc (str "\n  " (pr-str doc)) "") sym (pr-str (vec targs)) tret))))

;; every ported namespace needs babashka.ffi instead of coffi, plus the
;; colour packer; raylib.support loads the library once. Rebuilt from the
;; parsed form rather than by regex, so a docstring or metadata survives.
(defn rewrite-ns-node [node]
  (let [forms (n/sexpr node)
        nm (second forms)
        doc (when (string? (nth forms 2 nil)) (nth forms 2))]
    (str "(ns " nm "\n"
         (when doc (str "  " (pr-str doc) "\n"))
         "  (:require\n"
         "   [babashka.ffi :refer [defcfn]]\n"
         ;; helper code left in these files still writes ::mem/int and
         ;; ::rs/color as type tags; the aliases keep those readable
         "   [raylib.support :as mem]\n"
         "   [raylib.support :as rs]\n"
         "   [raylib.support :refer [pack-color]]))")))

(def support-ns
  "(ns raylib.support
  \"Library loading and colour packing for the ported raylib bindings.\"
  (:require [babashka.ffi :as ffi]))

(ffi/load-system-library \"raylib\")

(defn pack-color
  \"raylib's Color is four bytes; babashka.ffi passes it as a packed :uint.
  Accepts {:r :g :b :a} or an already packed integer.\"
  [c]
  (if (map? c)
    (bit-or (:r c 0)
            (bit-shift-left (:g c 0) 8)
            (bit-shift-left (:b c 0) 16)
            (bit-shift-left (:a c 255) 24))
    c))
")

;; Simpler and more predictable than splicing nodes: walk the top-level forms,
;; re-emit defcfn ones, copy everything else verbatim.
(defn port-file-text [f]
  (let [rel (str (fs/relativize src-root f))
        out (fs/file out-root rel)
        txt (slurp (str f))
        has-bindings? (str/includes? txt "(defcfn")
        ;; coffi infrastructure (library loading, type extensions, struct
        ;; layouts) is replaced by raylib.support, so it is not ported
        infra? (or (and (not has-bindings?) (str/includes? txt "coffi"))
                   ;; replaced by stubs below: nREPL server, struct layouts,
                   ;; library loading
                   (contains? #{"nrepl.clj" "structs.clj" "core.clj" "internals.clj"}
                              (fs/file-name f)))
        forms (n/children (z/root (z/of-string txt)))
        stats (atom {:ported 0 :stubbed 0})
        out-str
        (str/join
         (for [node forms]
           (cond
             (and has-bindings? (= :list (n/tag node)) (= 'ns (first (n/sexpr node))))
             (rewrite-ns-node node)

             ;; coffi struct layouts have no meaning here
             (and (= :list (n/tag node)) (= 'defalias (first (n/sexpr node))))
             ""

             (and (= :list (n/tag node)) (= 'defcfn (first (n/sexpr node))))
             (if-let [b (form->binding node)]
               (let [txt (emit-binding b)]
                 (swap! stats update (if (str/starts-with? txt "(defn ") :stubbed :ported) inc)
                 txt)
               (n/string node))

             :else (n/string node))))]
    (when-not infra?
      (fs/create-dirs (fs/parent out))
      ;; a namespace of plain data keeps its own ns form
      (spit (str out) out-str))
    (assoc @stats :file rel :skipped (boolean infra?))))

(fs/create-dirs out-root)
(fs/create-dirs (fs/file out-root "raylib"))
(spit (str (fs/file out-root "raylib" "support.clj")) support-ns)

(def results (mapv port-file-text (concat (fs/glob src-root "*.clj")
                                          (fs/glob src-root "**/*.clj"))))

;; The examples also reach for namespaces that are not bindings: an nREPL
;; server, a stats overlay, the coffi struct layouts and library loading.
;; Written as .bb files, which babashka prefers over .clj, so these win
;; wherever they sit on the classpath.
(defn stub! [rel src]
  (let [f (fs/file out-root rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) src)))

(stub! "raylib/core.bb"
       "(ns raylib.core\n  \"Library loading lives in raylib.support for the port.\")\n")
(stub! "raylib/structs.bb"
       "(ns raylib.structs\n  \"Struct layouts are unused: babashka.ffi passes Color packed and refuses\n  the rest.\")\n")
(stub! "raylib/internals.bb"
       "(ns raylib.internals\n  \"coffi type extensions are unused: babashka.ffi has :bool and :uint8.\")\n")
(stub! "raylib/nrepl.bb"
       "(ns raylib.nrepl\n  \"No nREPL server in the port.\")\n\n(defn start [& _] nil)\n(defn stop [& _] nil)\n")
(stub! "debug_stats.bb"
       (str "(ns debug-stats\n  \"No stats overlay in the port.\")\n\n"
            "(defn enable! [& _] nil)\n(defn disable! [& _] nil)\n"
            "(defn toggle! [& _] nil)\n(defn visible? [& _] false)\n"
            "(defn set-custom-stat! [& _] nil)\n(defn init [& _] nil)\n"
            "(defn update! [& [game]] game)\n(defn draw! [& [game]] game)\n"
            "(defn tick [& [game]] game)\n(defn draw [& [game]] game)\n"))

(println (format "%-34s %7s %8s" "namespace" "ported" "stubbed"))
(doseq [r (sort-by :file results)]
  (when (pos? (+ (:ported r) (:stubbed r)))
    (println (format "%-34s %7d %8d" (:file r) (:ported r) (:stubbed r)))))
(println (format "%-34s %7d %8d" "TOTAL"
                 (reduce + (map :ported results))
                 (reduce + (map :stubbed results))))
