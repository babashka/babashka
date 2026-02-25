(ns babashka.impl.clojure.java.io
  {:no-doc true}
  (:require [babashka.impl.classpath :as cp]
            [clojure.java.io :as io]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.types :as types])
  (:import [java.io File ]))

;;;; datafy

(defmulti as-file types/type-impl)

(defmethod as-file :sci.impl.protocols/reified [x]
  (let [methods (types/getMethods x)]
    ((get methods 'as-file) x)))

(defmethod as-file :default [x]
  (io/as-file x))

(defmulti as-url types/type-impl)

(defmethod as-file :sci.impl.protocols/reified [x]
  (let [methods (types/getMethods x)]
    ((get methods 'as-url) x)))

(defmethod as-url :default [x]
  (io/as-url x))

;; patch this impl with new impl of as-file
(defn ^String as-relative-path
  "Take an as-file-able thing and return a string if it is
   a relative path, else IllegalArgumentException."
  {:added "1.2"}
  [x]
  (let [^File f (as-file x)]
    (if (.isAbsolute f)
      (throw (IllegalArgumentException. (str f " is not a relative path")))
      (.getPath f))))

;; patch this impl with new impl of as-file and as-relative-path
(defn ^java.io.File file
  "Returns a java.io.File, passing each arg to as-file.  Multiple-arg
   versions treat the first argument as parent and subsequent args as
   children relative to the parent."
  {:added "1.2"}
  ([arg]
   (as-file arg))
  ([parent child]
   (File. ^File (as-file parent) ^String (as-relative-path child)))
  ([parent child & more]
   (reduce file (file parent child) more)))

(def io-ns (sci/create-ns 'clojure.java.io nil))

(def io-namespace
  {'Coercions (sci/new-var 'Coercions {:methods #{'as-file 'as-url}
                                       :ns io-ns} {:ns io-ns})
   'as-relative-path (copy-var as-relative-path io-ns {:copy-meta-from clojure.java.io/as-relative-path})
   'as-file (copy-var as-file io-ns {:copy-meta-from clojure.java.io/as-file})
   'file (copy-var file io-ns {:copy-meta-from clojure.java.io/file})
   'as-url (copy-var as-url io-ns {:copy-meta-from clojure.java.io/as-url})
   'copy (copy-var io/copy io-ns)
   'delete-file (copy-var io/delete-file io-ns)
   'input-stream (copy-var io/input-stream io-ns)
   'make-parents (copy-var io/make-parents io-ns)
   'output-stream (copy-var io/output-stream io-ns)
   'reader (copy-var io/reader io-ns)
   'writer (copy-var io/writer io-ns)
   'resource (sci/copy-var cp/resource io-ns)})
