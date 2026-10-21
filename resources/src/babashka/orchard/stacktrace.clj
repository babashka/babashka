(ns orchard.stacktrace
  "Stand-in for orchard.stacktrace, with sci's stack frames."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [sci.core :as sci]))

(defn- sci-frame
  "Returns a CIDER frame with nREPL, babashka and clojure.core frames flagged as tooling."
  [{:keys [ns name local file line column]}]
  (let [ns (str ns)
        fn-name (str (or name "fn") (when local (str "#" local)))]
    {:name (str ns "/" fn-name)
     :file (or file "NO_SOURCE_FILE")
     :line (or line 0)
     :column column
     :ns ns
     :fn fn-name
     :flags (if (or (str/starts-with? ns "nrepl.") (str/starts-with? ns "babashka.")
                    (= "clojure.core" ns))
              #{:clj :tooling}
              #{:clj :project})}))

(defn- java-frame
  "A JVM frame; sci's own frames are flagged tooling, which CIDER hides."
  [[class method file line]]
  (let [class (str class)]
    {:name (str class "/" method)
     :class class
     :method (str method)
     :file (str file)
     :line (or line 0)
     :flags (if (or (str/starts-with? class "sci.") (str/starts-with? class "babashka."))
              #{:java :tooling}
              #{:java})}))

(defn analyze
  "Returns the causes of `ex`, innermost last, with sci or JVM stack frames.
  Unwraps sci errors and preserves their sci frames."
  [^Throwable ex]
  (let [sci-trace (map sci-frame (try (sci/stacktrace ex) (catch Throwable _ nil)))
        ex (if (= :sci/error (:type (ex-data ex))) (or (ex-cause ex) ex) ex)]
    (loop [causes [] e ex]
      (if e
        (let [data (ex-data e)
              trace (if (and (seq sci-trace) (identical? e ex))
                      sci-trace
                      (map java-frame (:trace (Throwable->map e))))]
          (recur (conj causes (cond-> {:class (.getName (class e))
                                       :message (or (ex-message e) "")
                                       :stacktrace (vec trace)}
                                (:type data) (assoc :phase (some-> (:phase data) name))
                                (seq (dissoc data :type :line :column :file :phase :sci.impl/callstack))
                                (assoc :data (with-out-str (pp/pprint (dissoc data :sci.impl/callstack))))))
                 (.getCause e)))
        causes))))
