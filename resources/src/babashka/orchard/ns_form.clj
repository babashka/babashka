(ns orchard.ns-form
  "Read the `ns` form of a source file and take it apart, without loading or
  evaluating anything."
  {:added "0.46"}
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (clojure.lang LineNumberingPushbackReader TaggedLiteral)))

(defn- ns-form? [form]
  (and (seq? form)
       (contains? '#{ns clojure.core/ns} (first form))))

(defn- branch
  "The branch of the reader conditional `rc` for `platform`, wrapped in a
  vector so that a nil branch differs from no branch."
  [rc platform]
  (some (fn [[feature form]]
          (when (contains? #{platform :default} feature)
            [form]))
        (partition 2 (:form rc))))

(defn for-platform
  "`form` with its reader conditionals replaced by their branches for
  `platform` (`:clj` or `:cljs`). A conditional without such a branch goes
  away, like it does when the reader reads it. Like the reader, this throws
  when a splicing conditional's branch isn't a list or a vector."
  [form platform]
  (letfn [(elements [coll]
            (mapcat (fn [x]
                      (if (reader-conditional? x)
                        (if-let [[picked] (branch x platform)]
                          (if (:splicing? x)
                            (if (sequential? picked)
                              (elements picked)
                              (throw (IllegalArgumentException.
                                      "Spliced form in a reader conditional must be a list or a vector")))
                            [(for-platform picked platform)])
                          [])
                        [(for-platform x platform)]))
                    coll))]
    (cond
      (reader-conditional? form) (when-let [[picked] (branch form platform)]
                                   (for-platform picked platform))
      (seq? form) (with-meta (apply list (elements form)) (meta form))
      (vector? form) (with-meta (vec (elements form)) (meta form))
      (set? form) (with-meta (set (elements form)) (meta form))
      (map? form) (with-meta (into {}
                                   (map (fn [[k v]]
                                          [(for-platform k platform)
                                           (for-platform v platform)]))
                                   form)
                    (meta form))
      :else form)))

(defn- read-forms
  "Call `f` with a function that reads the next form of `source`, or returns
  `::eof` at its end. Reader conditionals stay in the forms, and nothing gets
  evaluated. A `LineNumberingPushbackReader` gets read as it is."
  [source f]
  (with-open [^LineNumberingPushbackReader reader (if (instance? LineNumberingPushbackReader source)
                                                    source
                                                    (LineNumberingPushbackReader. (io/reader source)))]
    (binding [*read-eval* false
              ;; Only #inst and #uuid get read, by `default-data-readers`.
              *data-readers* {}
              *default-data-reader-fn* tagged-literal]
      (f #(read {:read-cond :preserve, :eof ::eof} reader)))))

(defn- read-until
  "Read the forms of `source` until `(pick form)` returns something, and
  return that."
  [source pick]
  (try
    (read-forms source (fn [read-form]
                         (loop []
                           (let [form (read-form)]
                             (when-not (identical? ::eof form)
                               (or (pick form) (recur)))))))
    (catch Exception _)
    ;; from something nested very deep
    (catch StackOverflowError _)))

(defn read-ns-form
  "Read the first `ns` form from `source`, which can be anything
  `clojure.java.io/reader` accepts (a File, a URL, a path or a Reader).

  With `platform` (`:clj` or `:cljs`), reader conditionals get resolved for
  it, and this is the first `ns` form on that platform. Without `platform`,
  they stay in, so the form might be a conditional around `ns` forms, as in
  `#?(:clj (ns a ...) :cljs (ns a ...))`, for `for-platform` to resolve. A
  file with a separate conditional `ns` form for each platform needs
  `platform`, as only the first of those is found without it.

  Nothing gets evaluated: `#=` makes the form unreadable, and tagged literals
  come back as `clojure.lang.TaggedLiteral`s, except for `#inst` and `#uuid`.
  Returns nil when there's no `ns` form or `source` can't be read."
  ([source]
   (read-until source (fn [form]
                        (when (or (ns-form? form)
                                  (and (reader-conditional? form)
                                       (some ns-form? (take-nth 2 (rest (:form form))))))
                          form))))
  ([source platform]
   (read-until source (fn [form]
                        (let [form (for-platform form platform)]
                          (when (ns-form? form)
                            form))))))

(def ^:private libspec-clause-names
  #{"require" "use" "require-macros" "use-macros" "require-global"})

(def ^:private import-clause-names #{"import"})

(defn- clause-named?
  "Whether `clause` is an ns form clause named like `clause-names`, e.g.
  `#{\"require\"}`. Like `ns` itself, this goes by the name, so
  `(require ...)` counts too."
  [clause-names clause]
  (and (sequential? clause)
       (let [head (first clause)]
         (and (or (keyword? head) (symbol? head))
              (contains? clause-names (name head))))))

(defn- clauses
  "The clauses of `ns-form` named like `clause-names`."
  [ns-form clause-names]
  (filter (partial clause-named? clause-names) (drop 2 ns-form)))

(defn- libspec
  "The map for the libspec `spec` in the clause named `clause`. `prefix`
  qualifies a name that came from a prefix list."
  [clause prefix spec]
  (let [qualify #(if prefix (symbol (str prefix "." %)) %)]
    (if (sequential? spec)
      (into {:ns (qualify (first spec)), :clause clause}
            (map vec)
            (partition 2 (rest spec)))
      {:ns (qualify spec), :clause clause})))

(defn- prefix-list? [spec]
  (and (sequential? spec)
       (symbol? (first spec))
       (next spec)
       (not (keyword? (second spec)))))

(defn libspecs
  "The libspecs of the `:require`, `:use`, `:require-macros`, `:use-macros` and
  `:require-global` clauses of `ns-form`, as maps like

    {:ns clojure.string, :as str, :refer [join], :clause :require}

  with prefix lists expanded. `:ns` is a symbol, or a string for a
  JavaScript module in ClojureScript. Other options come as they're written,
  so a libspec from `:use` doesn't get a `:refer`. Resolve reader
  conditionals first, with `for-platform`."
  [ns-form]
  (for [[head & specs] (clauses ns-form libspec-clause-names)
        :let [clause (keyword (name head))]
        spec specs
        ;; flags like :reload
        :when (not (keyword? spec))
        libspec (if (prefix-list? spec)
                  (map #(libspec clause (first spec) %) (rest spec))
                  [(libspec clause nil spec)])]
    libspec))

(defn aliases
  "A map from each alias `ns-form` defines (with `:as` or `:as-alias`) to the
  namespace it stands for. What doesn't make sense as one, like a keyword for
  an alias in a libspec that's still being written, is left out."
  [ns-form]
  (into {}
        (comp (filter (comp (some-fn symbol? string?) :ns))
              (mapcat (fn [{:keys [ns as as-alias]}]
                        (for [alias [as as-alias]
                              :when (symbol? alias)]
                          [alias ns]))))
        (libspecs ns-form)))

(defn- spec-classes
  "The fully qualified names of the classes the import spec `spec` imports."
  [spec]
  (if (sequential? spec)
    (map #(symbol (str (first spec) "." %)) (rest spec))
    [spec]))

(defn imports
  "The fully qualified names of the classes the `:import` clauses of `ns-form`
  import, as symbols."
  [ns-form]
  (for [[_ & specs] (clauses ns-form import-clause-names)
        spec specs
        class-name (spec-classes spec)]
    class-name))

;;; What the ns form doesn't need

(defn- namespace-names
  "The namespaces the dotted name `s` could mention, as symbols: `a`, `a.b`
  and `a.b.c` for `a.b.c`, each also with its underscores turned into
  dashes, as in the package of a class a namespace defines."
  [s]
  (let [segments (str/split s #"\.")]
    (when (every? seq segments)
      (distinct (for [n (range 1 (inc (count segments)))
                      :let [prefix (str/join "." (take n segments))]
                      ns-name [prefix (str/replace prefix "_" "-")]]
                  (symbol ns-name))))))

(defn- usage-resolver
  "A reader resolver for the code of the namespace `ns-name`, whose aliases
  `aliases` maps to namespaces. It's what `::alias/kw` keywords and syntax
  quote ask, so it records the qualifiers and the names it gets asked about
  in `usage`, unless that's nil."
  [ns-name aliases usage]
  (reify clojure.lang.LispReader$Resolver
    (currentNS [_] ns-name)
    ;; Syntax quote asks for a qualifier or a name as a class first, then as
    ;; an alias or a var, so those two are where they get recorded.
    (resolveClass [_ _] nil)
    (resolveAlias [_ sym]
      (some-> usage (swap! update :qualifiers conj sym))
      ;; An alias the namespace doesn't have stands for itself, so that
      ;; ::unknown/kw reads instead of throwing.
      (or (some-> (get aliases sym) str symbol) sym))
    (resolveVar [_ sym]
      (some-> usage (swap! update :names conj sym))
      nil)))

(defn- record-symbol
  "Record the symbol `sym` in `usage`: its namespace part in `:qualifiers`,
  or the symbol itself (`Foo.` as `Foo`) and the first part of a dotted one
  in `:names`. The namespaces a dotted name could mention go in `:mentions`."
  [usage sym]
  (swap! usage
         (fn [usage]
           (if-let [q (namespace sym)]
             (-> usage
                 (update :qualifiers conj (symbol q))
                 (update :mentions into (namespace-names q)))
             (let [n (str/replace (name sym) #"(.)\.$" "$1")
                   segments (str/split n #"\.")]
               (cond-> (update usage :names conj (symbol n))
                 ;; foo.records.Rec, 'foo.core, and in ClojureScript
                 ;; goog.string.format, EventType.CLICK or obj.prop
                 (and (next segments) (every? seq segments))
                 (-> (update :names conj (symbol (first segments)))
                     (update :mentions into (namespace-names n)))))))))

(defn- record-usage
  "Record the symbols in `form`, its metadata included, and the namespaces of
  its keywords, in `usage`. A string that could be a class name goes in
  `:strings`."
  [usage form]
  (letfn [(walk [x]
            (when-let [m (meta x)]
              (walk m)
              ;; ^"File" resolves like ^File.
              (when (string? (:tag m))
                (record-symbol usage (symbol (:tag m)))))
            (cond
              (symbol? x) (record-symbol usage x)
              ;; In syntax quote, ^"File" turns into code with a "File" in it.
              (and (string? x) (re-matches #"[\p{L}_$][\p{L}\p{N}_$]*" x))
              (swap! usage update :strings conj (symbol x))
              (keyword? x) (some->> (namespace x) namespace-names (swap! usage update :mentions into))
              (or (reader-conditional? x) (instance? TaggedLiteral x)) (walk (:form x))
              (coll? x) (run! walk x)))]
    (walk form)))

(defn- analyzable-libspec
  "The options of the libspec `spec`, when it's one whose parts can be
  told apart as used or unused: a vector or a list with an alias or a list
  of names to refer. Bare requires might be there for their side effects,
  `:refer :all` and `:rename` need the namespace loaded to judge, and
  prefix lists and reader conditionals are left alone too."
  [spec]
  (when (and (sequential? spec)
             (not (prefix-list? spec))
             (even? (count (rest spec))))
    (let [ns (first spec)
          opts (apply hash-map (rest spec))
          names? #(and (sequential? %) (seq %) (every? symbol? %))]
      (when (and (or (symbol? ns) (string? ns))
                 (every? #{:as :as-alias :refer :refer-macros :include-macros} (keys opts))
                 (some #{:as :as-alias :refer :refer-macros} (keys opts))
                 (every? symbol? (keep opts [:as :as-alias]))
                 (every? names? (keep opts [:refer :refer-macros])))
        (assoc opts :ns ns)))))

(defn- maybe-cljs?
  "Whether the code can be ClojureScript, going by its `platform`."
  [platform]
  (contains? #{:cljs nil} platform))

(defn- unused-libspec
  "What the libspec in the clause named `clause` with the options `opts`
  doesn't need, going by `context`, which has the code's `usage`, the
  `:imported` namespaces (the ones that define imported classes) and the
  `:platform`. That's the whole libspec (`:remove true`), or the alias
  (`:as`, `:as-alias`) and the names (`:refer`, `:refer-macros`) it can do
  without, or nil."
  [{:keys [qualifiers names mentions imported platform]} clause
   {:keys [ns as as-alias refer refer-macros] :as opts}]
  (let [name-used? #(or (names %) (qualifiers %))
        ;; In ClojureScript, the alias of a JavaScript module is a value too.
        alias-used? #(or (qualifiers %)
                         (and (or (string? ns) (maybe-cljs? platform)) (names %)))
        unused-names #(not-empty (vec (distinct (remove name-used? %))))
        ;; A libspec with nothing but :as-alias doesn't load its namespace.
        loads? (some #{:as :refer :refer-macros :include-macros} (keys opts))
        ;; as in clojure.string/join, 'clojure.string, :clojure.string/kw, or
        ;; for a class it defines
        needed? (some #(contains? % (symbol ns)) [qualifiers mentions imported])]
    (if-not (or (some-> as alias-used?)
                (some-> as-alias alias-used?)
                (some name-used? refer)
                (some name-used? refer-macros)
                (and loads? needed?))
      {:ns ns, :clause clause, :remove true}
      (let [parts (cond-> {}
                    (and as (not (alias-used? as))) (assoc :as as)
                    (and as-alias (not (alias-used? as-alias))) (assoc :as-alias as-alias)
                    (unused-names refer) (assoc :refer (unused-names refer))
                    (unused-names refer-macros) (assoc :refer-macros (unused-names refer-macros)))]
        (when (seq parts)
          (merge {:ns ns, :clause clause} parts))))))

(defn- direct-specs
  "The specs written straight into the clauses of `ns-form` named like
  `clause-names`, as `[clause spec]` pairs."
  [ns-form clause-names]
  (for [[head & specs] (clauses ns-form clause-names)
        spec specs
        :when (not (or (keyword? spec) (reader-conditional? spec)))]
    [(keyword (name head)) spec]))

(defn- once
  "The elements of `coll` for which `key-fn` returns what it returns for no
  other element."
  [key-fn coll]
  (let [counts (frequencies (map key-fn coll))]
    (filter #(= 1 (counts (key-fn %))) coll)))

(defn- unused-imports
  "The classes the `:import` clauses of `ns-form` import that `context`
  doesn't say are used."
  [{:keys [qualifiers names mentions strings platform]} ns-form]
  (->> (direct-specs ns-form import-clause-names)
       (mapcat (fn [[_ spec]]
                 (when (or (symbol? spec) (and (sequential? spec) (every? symbol? spec)))
                   (spec-classes spec))))
       (once identity)
       (remove (fn [class-name]
                 (let [simple-name (symbol (peek (str/split (str class-name) #"\.")))]
                   (or (names simple-name)
                       (qualifiers simple-name)
                       (strings simple-name)
                       ;; ClojureScript needs the import for
                       ;; goog.events.EventType.CLICK.
                       (and (maybe-cljs? platform) (mentions class-name))))))))

(defn unused
  "What the first `ns` form in `source` requires, refers and imports that
  the code after it doesn't use. `source` can be anything
  `clojure.java.io/reader` accepts. A `LineNumberingPushbackReader` gets
  read as it is, so the line it starts on carries over into read errors.
  `platform` is what the code is for: `:clj`, `:cljs`, or `:cljc` for both.
  Without it, less goes, as the code might be either.

  This goes by reading the code, without loading or evaluating anything,
  so a name counts as used wherever it comes up: in a reader conditional
  for either platform, a quoted form, a comment form, a local binding of
  the same name. Only what's written straight into the `:require`,
  `:require-macros` and `:import` clauses gets judged; bare requires,
  `:refer :all`, `:rename`, prefix lists and anything in a reader
  conditional count as used.

  Returns nil when there's no `ns` form, and otherwise a map with

    :libspecs  maps like {:ns clojure.set, :clause :require, :remove true}
               for a libspec that can go, or like
               {:ns clojure.string, :clause :require, :as str, :refer [join]}
               naming the parts of one that can go
    :imports   the fully qualified names of the classes that can go

  Throws when `source` doesn't read. In a `.cljc` file that includes code
  that only reads once a platform is picked, like `#?@` splicing into a map
  literal."
  ([source] (unused source nil))
  ([source platform]
   (try
     (read-forms
      source
      (fn [read-form]
        (let [ns-form (binding [*reader-resolver* (usage-resolver 'user {} nil)]
                        (loop []
                          (let [form (read-form)]
                            (cond
                              (identical? ::eof form) nil
                              (ns-form? form) form
                              :else (recur)))))]
          (when (symbol? (second ns-form))
            (let [usage (atom {:qualifiers #{}, :names #{}, :mentions #{}, :strings #{}})
                  platform-forms [(for-platform ns-form :clj) (for-platform ns-form :cljs)]]
              ;; The ns form's own options, like :gen-class, can use classes
              ;; and names too.
              (run! #(record-usage usage %)
                    (remove (partial clause-named? (into libspec-clause-names import-clause-names))
                            (drop 2 ns-form)))
              (binding [*reader-resolver* (usage-resolver (second ns-form)
                                                          (into {} (map aliases) platform-forms)
                                                          usage)]
                (loop []
                  (let [form (read-form)]
                    (when-not (identical? ::eof form)
                      (record-usage usage form)
                      (recur)))))
              (let [context (assoc @usage
                                   :platform platform
                                   :imported (into #{}
                                                   (comp (mapcat imports)
                                                         (map #(str/replace (str %) #"\.[^.]+$" ""))
                                                         (map #(symbol (str/replace % "_" "-"))))
                                                   platform-forms))]
                {:libspecs (vec (keep (fn [[clause spec]]
                                        (some->> (analyzable-libspec spec)
                                                 (unused-libspec context clause)))
                                      ;; A namespace that comes up twice in a
                                      ;; clause is too much to sort out.
                                      (once (fn [[clause spec]]
                                              [clause (if (sequential? spec) (first spec) spec)])
                                            (direct-specs ns-form #{"require" "require-macros"}))))
                 :imports (vec (unused-imports context ns-form))}))))))
     (catch StackOverflowError _
       (throw (ex-info "The code is nested too deep to read" {}))))))
