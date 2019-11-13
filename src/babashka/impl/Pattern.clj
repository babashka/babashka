(ns babashka.impl.Pattern
  {:no-doc true})

(set! *warn-on-reflection* true)

(defmacro gen-wrapper-fn [method-name]
  `(defn ~method-name {:bb/export true}
     [~(with-meta 'x {:tag 'java.util.regex.Pattern})]
     (~(symbol (str "." method-name)) ~'x)))

(defmacro gen-wrapper-fn-2 [method-name]
  `(defn ~method-name {:bb/export true}
     [~(with-meta 'x {:tag 'java.util.regex.Pattern}) ~'y]
     (~(symbol (str "." method-name)) ~'x ~'y)))

(defmacro gen-constants [& constant-names]
  (let [defs (for [constant-name constant-names
                   :let [full-name (symbol (str "java.util.regex.Pattern/" constant-name))
                         meta {:bb/export-constant true
                               :bb/full-name (list 'quote full-name)}
                         constant-name (with-meta constant-name meta)]]
               `(def ~constant-name
                  ~full-name))]
    `(do ~@defs)))

(gen-constants CANON_EQ CASE_INSENSITIVE COMMENTS DOTALL LITERAL MULTILINE
               UNICODE_CASE UNICODE_CHARACTER_CLASS UNIX_LINES)

(gen-wrapper-fn asPredicate)

(defn compile*
  ([^String s]
   (java.util.regex.Pattern/compile s))
  ([^String s ^long flags]
   (java.util.regex.Pattern/compile s flags)))

(gen-wrapper-fn flags)

(gen-wrapper-fn-2 matcher)

(defn matches [^String regex ^CharSequence input]
  (java.util.regex.Pattern/matches regex input))

(gen-wrapper-fn pattern)

(defn pattern-quote [s]
  (java.util.regex.Pattern/quote s))

(defn ^:bb/export split
  ([^java.util.regex.Pattern p ^CharSequence input]
   (.split p input))
  ([^java.util.regex.Pattern p ^CharSequence input ^long limit]
   (.split p input limit)))

(gen-wrapper-fn-2 splitAsStream)

(def pattern-bindings
  (-> (reduce (fn [acc [k v]]
                (let [m (meta v)]
                  (cond (:bb/export m)
                        (assoc acc (symbol (str "." k))
                               @v),
                        (:bb/export-constant m)
                        (assoc acc (symbol (:bb/full-name m))
                               @v)
                        :else acc)))
              {}
              (ns-publics *ns*))
      ;; static method
      (assoc (symbol "java.util.regex.Pattern/compile") compile*)
      (assoc (symbol "java.util.regex.Pattern/quote") pattern-quote)
      (assoc (symbol "java.util.regex.Pattern/matches") matches)))
