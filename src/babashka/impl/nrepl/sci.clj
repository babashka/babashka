(ns babashka.impl.nrepl.sci
  "Shared SCI helpers for REPLs: completions, lookup, namespace resolution.
   Extracted from nREPL server to be reusable by console REPL."
  {:author "Michiel Borkent"
   :no-doc true}
  (:require
   [clojure.java.io :as io]
   [clojure.reflect]
   [clojure.string :as str]
   [sci.core :as sci]))

(set! *warn-on-reflection* true)

;;;; Namespace resolution

(defn the-sci-ns [ctx ns-sym]
  (sci/eval-form ctx (list 'clojure.core/the-ns (list 'quote ns-sym))))

;;;; Lookup

(defn- resolve-file
  "Returns lookup result m without :resource, and with :file as its classpath
  resource URL, or its file URL if the file exists."
  [{:keys [file resource] :as m}]
  (-> m
      (dissoc :resource)
      (cond-> file (assoc :file (or resource
                                    (let [f (io/file file)]
                                      (when (.exists f) (str (io/as-url f))))
                                    file)))))

(defn lookup
  "Returns metadata map for sym-str, or nil.
   When ns-str is provided, resolves in that namespace using ns-resolve.
   Otherwise resolves in the current namespace using resolve."
  [ctx sym-str & {:keys [ns-str]}]
  (try
    (let [sci-ns (when ns-str
                   (the-sci-ns ctx (symbol ns-str)))
          ns-str (or ns-str (str @sci/ns))
          m (sci/binding [sci/ns (or sci-ns @sci/ns)]
              (sci/eval-string* ctx (format "
(let [ns '%s
      full-sym '%s
      resource-fn (resolve 'clojure.java.io/resource)
      doc-sym (get '{& fn catch try finally try} full-sym full-sym)]
  (if-let [m (when (special-symbol? full-sym)
               ((resolve 'clojure.repl/special-doc) doc-sym))]
    {:doc (:doc m)
     :forms (:forms m)
     :name full-sym
     :ns 'clojure.core
     :special-form true
     :url (:url m)}
    (let [v (ns-resolve ns doc-sym)]
      (when (var? v)
       (let [m (meta v)]
        {:arglists (:arglists m)
         :doc (:doc m)
         :name (:name m)
         :ns (some-> m :ns ns-name)
         :macro (:macro m)
         :added (:added m)
         :deprecated (:deprecated m)
         :val @v
         :line (:line m)
         :column (:column m)
         :file (:file m)
         :resource (when resource-fn (some-> (:file m) resource-fn str))})))))" ns-str sym-str)))]
      (some-> m resolve-file))
    (catch Throwable _ nil)))

;;;; Completions

(defn- class-sym->completion
  "Converts a class symbol like clojure.lang.RT to [full-class-name simple-name type]."
  [sym]
  (let [n (name sym)
        idx (str/last-index-of n ".")]
    [n
     (subs n (inc idx))
     "class"]))

(defn fuzzy-matches?
  "Returns true if prefix is made of leading pieces of the parts of s, split
  on separator, in order.
  The first piece starts s, and later parts may be skipped."
  [^String prefix ^String s separator]
  (let [pn (.length prefix)
        sn (.length s)
        separator (char separator)]
    (cond (zero? pn) true
          (zero? sn) false
          (not= (.charAt prefix 0) (.charAt s 0)) false
          :else (loop [pi 1
                       si 1
                       skipping false]
                  (cond (>= pi pn) true
                        (>= si sn) false
                        :else (let [pc (.charAt prefix pi)
                                    sc (.charAt s si)
                                    match (= pc sc)]
                                (cond (= sc separator)
                                      (recur (if match (inc pi) pi) (inc si) false)
                                      (or skipping (not match)) (recur pi (inc si) true)
                                      :else (recur (inc pi) (inc si) false))))))))

(def ^:private special-forms
  ["def" "if" "do" "quote" "var" "recur" "throw" "try" "catch" "monitor-enter" "monitor-exit"
   "new" "set!" "true" "false" "nil"])

(defn- special-form-completions [query]
  (when-not (str/includes? query "/")
    (keep (fn [form]
            (when (fuzzy-matches? query form \-)
              [nil form "special-form"]))
          special-forms)))

(defn- ns-entries
  "Returns an entry for each mapping in (ns-fn ns-sym), named by its key.
  A var gives [:var ns name type], with type \"macro\", \"function\" or \"var\".
  A class gives [:class class name].
  Any other value gives [:var ns-sym name nil].
  ns-fn is \"ns-map\" or \"ns-publics\"."
  [ctx ns-fn ns-sym]
  (sci/eval-string* ctx (format "
(mapv (fn [[k v]]
        (cond (var? v)
              (let [m (meta v)]
                [:var (str (or (some-> (:ns m) ns-name) '%2$s)) (str k)
                 (cond (:macro m) \"macro\" (:arglists m) \"function\" :else \"var\")])
              (class? v) [:class v (str k)]
              :else [:var (str '%2$s) (str k) nil]))
      (%1$s '%2$s))" ns-fn ns-sym)))

(defn match [_alias->ns ns->alias query [sym-ns sym-name qualifier type]]
  (let [has-slash? (str/includes? query "/")]
    (or (when (and (= "class" qualifier) (fuzzy-matches? query sym-name \.))
          [sym-ns sym-name "class"])
        (when (and (identical? :unqualified qualifier) (fuzzy-matches? query sym-name \-))
          [sym-ns sym-name type])
        ;; Namespace-only entries (sym-name is nil) - match on namespace name
        (when (and (nil? sym-name) sym-ns (fuzzy-matches? query sym-ns \.))
          [nil sym-ns "namespace"])
        ;; Qualified symbol matching
        (when (and sym-ns sym-name)
          (let [alias (get ns->alias (symbol sym-ns))
                alias-qualified (when alias (str alias "/" sym-name))
                ns-qualified (str sym-ns "/" sym-name)]
            (or ;; Always try alias-qualified matching (for `quux<TAB>` -> `quux/foo`)
             (when (and alias-qualified (fuzzy-matches? query alias-qualified \-))
               [sym-ns alias-qualified type])
                ;; Full namespace-qualified matching only when query has a slash
             (when (and has-slash? (fuzzy-matches? query ns-qualified \-))
               [sym-ns ns-qualified type])))))))

(defn- member-simple-name
  "Extracts simple name from a possibly qualified name (e.g., java.lang.String -> String)"
  [s]
  (let [idx (str/last-index-of s ".")]
    (if idx (subs s (inc idx)) s)))

(defn ns-imports->completions [ctx query-ns query]
  (let [[ns-part name-part] (str/split query #"/")
        resolved (sci/eval-string* ctx
                                   (pr-str `(let [resolved# (resolve '~query-ns)]
                                              (when-not (var? resolved#)
                                                resolved#))))
        class-simple-name (when resolved (member-simple-name (str resolved)))]
    (when
     resolved
      (->>
       (clojure.reflect/reflect resolved)
       :members
       (into
        []
        (comp
         (filter (comp :public :flags))
         (filter (fn [{member-sym :name :keys [flags]}]
                   (let [static? (:static flags)
                         simple-name (member-simple-name (str member-sym))
                         constructor? (= simple-name class-simple-name)
                         member-str (cond
                                      constructor? "new"
                                      static? (str member-sym)
                                      :else (str "." member-sym))]
                     (or (not name-part) (str/starts-with? member-str name-part)))))
         (map
          (fn [{:keys [name parameter-types flags]}]
            (let [static? (:static flags)
                  simple-name (member-simple-name (str name))
                  constructor? (= simple-name class-simple-name)]
              [ns-part
               (cond
                 constructor? (str ns-part "/new")
                 static? (str ns-part "/" name)
                 :else (str ns-part "/." name))
               (cond
                 constructor? "constructor"
                 (and static? parameter-types) "static-method"
                 static? "static-field"
                 parameter-types "method"
                 :else "field")])))))))))

(defn import-symbols->completions [imports query]
  (doall
   (sequence
    (comp
     (map key)
     (filter
      (fn [sym-name]
        (fuzzy-matches? query (str sym-name) \.)))
     (map (fn [class-name]
            [nil (str class-name) "class"])))
    imports)))

(def ^:private keyword-table
  (delay
    (let [f (.getDeclaredField clojure.lang.Keyword "table")]
      (.setAccessible f true)
      (.get f nil))))

(defn keyword-completions
  "Completions for keywords from the intern table.
   Handles :foo, :ns/foo, ::foo (current ns), and ::alias/foo."
  [ctx query]
  (when (str/starts-with? query ":")
    (let [^java.util.concurrent.ConcurrentHashMap table @keyword-table
          double-colon? (str/starts-with? query "::")
          after-colons (subs query (if double-colon? 2 1))
          has-slash? (str/includes? after-colons "/")
          alias-part (when (and double-colon? has-slash?)
                       (first (str/split after-colons #"/")))
          ;; Resolve :: prefix to actual namespace
          resolved-ns (when double-colon?
                        (if alias-part
                          ;; ::alias/foo -> resolve alias
                          (let [alias->ns (sci/eval-string* ctx
                                            "(let [m (ns-aliases *ns*)]
                                               (zipmap (map str (keys m))
                                                       (map (comp str ns-name) (vals m))))")]
                            (get alias->ns alias-part))
                          ;; ::foo -> current namespace
                          (str (sci/eval-string* ctx "(ns-name *ns*)"))))
          table-prefix (if resolved-ns
                         (str resolved-ns "/" (if has-slash?
                                               (subs after-colons (inc (count alias-part)))
                                               after-colons))
                         after-colons)
          display-prefix (if double-colon?
                           (str "::" (if alias-part (str alias-part "/") ""))
                           ":")
          pat (re-pattern (str "^" (java.util.regex.Pattern/quote table-prefix)))]
      (doall
       (sequence
        (comp
         (filter (fn [k] (re-find pat (str k))))
         (map (fn [k]
                (let [k-str (str k)
                      display (if resolved-ns
                                ;; Strip resolved ns, show with alias/:: prefix
                                (let [name-part (subs k-str (inc (count resolved-ns)))]
                                  (str display-prefix name-part))
                                (str ":" k-str))]
                  [nil display "keyword"]))))
        (keys table))))))

(defn fq-class->completions
  "Completions for fully qualified class names like java.lang.String"
  [classes query]
  (doall
   (sequence
    (comp
     (map key)
     (remove keyword?)
     (map str)
     (filter (fn [fq-class] (fuzzy-matches? query fq-class \.)))
     (map (fn [fq-class] [fq-class fq-class "class"])))
    classes)))

(defn- format-completions
  "Formats raw completion tuples into sorted maps with :candidate, :ns, :type."
  [query completions]
  {:completions
   (->> (map (fn [[namespace name type]]
               (cond->
                {:candidate (str name)}
                 namespace (assoc :ns (str namespace))
                 type (assoc :type (str type))))
             completions)
        distinct
        (sort-by (fn [{:keys [candidate]}]
                   [(not (str/starts-with? candidate query))
                    (count candidate)
                    candidate])))})

(defn completions
  "Returns completions for the given query string using the SCI context.
   Returns a map with :completions (list of maps with :candidate, :ns, :type)."
  [ctx query]
  (when (and query (pos? (count query)))
    (try
      (if (str/starts-with? query ":")
        ;; Keyword queries — only keyword completions apply
        (format-completions query (keyword-completions ctx query))
        ;; Symbol, class, namespace completions
        (let [has-namespace? (str/includes? query "/")
              query-ns (when has-namespace? (symbol (first (str/split query #"/"))))
              current-ns-sym (sci/eval-string* ctx "(ns-name *ns*)")
              from-current-ns (map (fn [[kind a b type]]
                                     (cond (not= :class kind) [a b :unqualified type]
                                           (instance? Class a) (class-sym->completion (symbol (.getName ^Class a)))
                                           :else [nil b :unqualified nil]))
                                   (ns-entries ctx "ns-map" current-ns-sym))
              ;; Types from current namespace (deftype/defrecord names)
              from-types (let [types (get-in @(:env ctx) [:namespaces current-ns-sym :types])]
                           (map (fn [[k _]] [nil (str k) :unqualified]) types))
              alias->ns (sci/eval-string* ctx "(let [m (ns-aliases *ns*)] (zipmap (keys m) (map ns-name (vals m))))")
              ns->alias (zipmap (vals alias->ns) (keys alias->ns))
              from-aliased-nss (doall (mapcat
                                       (fn [ns]
                                         (map (fn [[_ _ sym type]]
                                                [(str ns) sym :qualified type])
                                              (ns-entries ctx "ns-publics" ns)))
                                       (distinct (vals alias->ns))))
              all-namespaces (->> (sci/eval-string* ctx "(all-ns)")
                                  (map (fn [ns]
                                         [(str ns) nil :qualified])))
              from-imports (when query-ns (ns-imports->completions ctx (symbol query-ns) query))
              ns-found? (sci/eval-string* ctx (format "(find-ns '%s)" query-ns))
              fully-qualified-names (when-not from-imports
                                      (when (and has-namespace? ns-found?)
                                        (let [ns (get alias->ns query-ns query-ns)]
                                          (map (fn [[_ _ sym type]]
                                                 [(str ns) sym :qualified type])
                                               (ns-entries ctx "ns-publics" ns)))))
              svs (concat from-current-ns from-types from-aliased-nss all-namespaces fully-qualified-names)
              completions (keep (fn [entry]
                                  (match alias->ns ns->alias query entry))
                                svs)
              completions (concat completions from-imports)
              import-symbols (import-symbols->completions (:imports @(:env ctx)) query)
              completions (concat completions import-symbols)
              fq-classes (fq-class->completions (:raw-classes @(:env ctx)) query)
              completions (concat completions fq-classes (special-form-completions query))]
          (format-completions query completions)))
      (catch Throwable e
        {:error e :completions []}))))

(comment
  (require '[sci.core :as sci])
  (def ctx (sci/init {:classes {'clojure.lang.RT clojure.lang.RT}}))
  (sci/eval-string* ctx "(import 'clojure.lang.RT)")
  (completions ctx "RT"))
