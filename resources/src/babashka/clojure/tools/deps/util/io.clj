;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.util.io
  "IO utilities. Consider using clojure.tools.deps.edn for reading deps.edn instead.
  IMPL namespace, subject to change without warning."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.string :as str])
  (:import
    [java.io Reader FileReader PushbackReader]))

(defonce ^:private nl (System/getProperty "line.separator"))

(defn printerrln
  "println to *err*"
  [& msgs]
  (binding [*out* *err*
            *print-readably* nil]
    (pr (str (str/join " " msgs) nl))
    (flush)))

(defn read-edn
  "Read the edn file from the specified `reader`.
  This file should contain a single edn value. Empty files return nil.
  The reader will be read to EOF and closed."
  [^Reader reader]
  (let [EOF (Object.)]
    (with-open [rdr (PushbackReader. reader)]
      (let [val (edn/read {:default tagged-literal :eof EOF} rdr)]
        (if (identical? EOF val)
          nil
          (if (not (identical? EOF (edn/read {:eof EOF} rdr)))
            (throw (ex-info "Invalid file, expected edn to contain a single value." {}))
            val))))))

(defn slurp-edn
  "Read the edn file specified by f, a string or File.
  An empty file will return nil."
  [f]
  (read-edn (FileReader. (jio/file f))))

(defn write-file
  "Write the string s to file f. Creates parent directories for f if they don't exist."
  [f s]
  (let [the-file (jio/file f)
        parent (.getParentFile the-file)]
    (when-not (.exists parent)
      (when-not (.mkdirs parent)
        (let [parent-name (.getCanonicalPath parent)]
          (throw (ex-info (str "Can't create directory: " parent-name) {:dir parent-name})))))
    (spit the-file s)))

(comment
  (slurp-edn "deps.edn")
  )
