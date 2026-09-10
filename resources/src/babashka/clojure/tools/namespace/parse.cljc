;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Parse Clojure namespace (ns) declarations and extract
  dependencies."}
  clojure.tools.namespace.parse
  (:require #?(:clj [clojure.tools.reader :as reader]
               :cljs [cljs.tools.reader :as reader])
            [clojure.set :as set]))

(defn comment?
  "Returns true if form is a (comment ...)"
  [form]
  (and (list? form) (= 'comment (first form))))

(defn ns-decl?
  "Returns true if form is a (ns ...) declaration."
  [form]
  (and (list? form) (= 'ns (first form))))

(def clj-read-opts
  "Map of options for tools.reader/read allowing reader conditionals
  with the :clj feature enabled."
  {:read-cond :allow
   :features #{:clj}})

(def cljs-read-opts
  "Map of options for tools.reader/read allowing reader conditionals
  with the :cljs feature enabled."
  {:read-cond :allow
   :features #{:cljs}})

(defn read-ns-decl
  "Attempts to read a (ns ...) declaration from a reader, and returns
  the unevaluated form. Returns the first top-level ns form found.
  Returns nil if ns declaration cannot be found. Throws exception on
  invalid syntax.

  Note that read can execute code (controlled by
  tools.reader/*read-eval*), and as such should be used only with
  trusted sources. read-opts is passed through to tools.reader/read,
  defaults to clj-read-opts"
  ([rdr]
   (read-ns-decl rdr nil))
  ([rdr read-opts]
   (let [opts (assoc (or read-opts clj-read-opts)
                     :eof ::eof)]
     (loop []
       (let [form (reader/read opts rdr)]
         (cond
           (ns-decl? form) form
           (= ::eof form) nil
           :else (recur)))))))

;;; Parsing dependencies

(defn- prefix-spec?
  "Returns true if form represents a libspec prefix list like
  (prefix name1 name1) or [com.example.prefix [name1 :as name1]]"
  [form]
  (and (sequential? form)  ; should be a list, but often is not
       (symbol? (first form))
       (not-any? keyword? form)
       (< 1 (count form))))  ; not a bare vector like [foo]

(defn- option-spec?
  "Returns true if form represents a libspec vector containing optional
  keyword arguments like [namespace :as alias] or
  [namespace :refer (x y)] or just [namespace]"
  [form]
  (and (sequential? form)  ; should be a vector, but often is not
       (or (symbol? (first form))
           (string? (first form)))
       (or (keyword? (second form))  ; vector like [foo :as f]
           (= 1 (count form)))))  ; bare vector like [foo]

(defn- deps-from-libspec [prefix form]
  (cond (prefix-spec? form)
          (mapcat (fn [f] (deps-from-libspec
                           (symbol (str (when prefix (str prefix "."))
                                        (first form)))
                           f))
                  (rest form))
	(option-spec? form)
          (when-not (= :as-alias (second form))
            (deps-from-libspec prefix (first form)))
	(symbol? form)
          (list (symbol (str (when prefix (str prefix ".")) form)))
	(keyword? form)  ; Some people write (:require ... :reload-all)
          nil
        (string? form) ; NPM dep, ignore
          nil
        :else
          (throw (ex-info "Unparsable namespace form"
                          {:reason ::unparsable-ns-form
                           :form form}))))

(def ^:private ns-clause-head-names
  "Set of symbol/keyword names which can appear as the head of a
  clause in the ns form."
  #{"use" "require" "require-macros"})

(def ^:private ns-clause-heads
  "Set of all symbols and keywords which can appear at the head of a
  dependency clause in the ns form."
  (set (mapcat (fn [name] (list (keyword name)
                                (symbol name)))
               ns-clause-head-names)))

(defn- deps-from-ns-form [form]
  (when (and (sequential? form)  ; should be list but sometimes is not
	     (contains? ns-clause-heads (first form)))
    (mapcat #(deps-from-libspec nil %) (rest form))))

(defn name-from-ns-decl
  "Given an (ns...) declaration form (unevaluated), returns the name
  of the namespace as a symbol."
  [decl]
  (second decl))

(defn deps-from-ns-decl
  "Given an (ns...) declaration form (unevaluated), returns a set of
  symbols naming the dependencies of that namespace.  Handles :use and
  :require clauses but not :load."
  [decl]
  (set (mapcat deps-from-ns-form decl)))
