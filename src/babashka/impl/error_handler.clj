(ns babashka.impl.error-handler
  (:refer-clojure :exclude [error-handler])
  (:require [babashka.impl.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as str]
            [sci.impl.callstack :as cs]))

(defn ruler [title]
  (println (apply str "----- " title " " (repeat (- 80 7 (count title)) \-))))

(defn split-stacktrace [stacktrace verbose?]
  (if verbose? [stacktrace]
      (let [stack-count (count stacktrace)]
        (if (<= stack-count 10)
          [stacktrace]
          [(take 5 stacktrace)
           (drop (- stack-count 5) stacktrace)]))))

(defn print-stacktrace
  [stacktrace {:keys [:verbose?]}]
  (let [stacktrace (cs/format-stacktrace stacktrace)
        segments (split-stacktrace stacktrace verbose?)
        [fst snd] segments]
    (run! println fst)
    (when snd
      (println "...")
      (run! println snd))))

(defn error-context [ex opts]
  (let [{:keys [:file :line :column]} (ex-data ex)]
    (when (and file line)
      (when-let [content (case file
                           "<expr>" (:expression opts)
                           "<preloads>" (:preloads opts)
                           (let [f (io/file file)]
                             (or (when (.exists f) (slurp f))
                                 (and (not (.isAbsolute f))
                                      (when-let [loader (:loader opts)]
                                        (:source (cp/getResource loader [file] nil)))))))]
        (let [matching-line (dec line)
              start-line (max (- matching-line 4) 0)
              end-line (+ matching-line 6)
              [before after] (->>
                              (str/split-lines content)
                              (map-indexed list)
                              (drop start-line)
                              (take (- end-line start-line))
                              (split-at (inc (- matching-line start-line))))
              snippet-lines (concat before
                                    [[nil (str (str/join "" (repeat (dec column) " "))
                                               (str "^--- " (ex-message ex)))]]
                                    after)
              indices (map first snippet-lines)
              max-size (reduce max 0 (map (comp count str) indices))
              snippet-lines (map (fn [[idx line]]
                                   (if idx
                                     (let [line-number (inc idx)]
                                       (str (format (str "%" max-size "d: ") line-number) line))
                                     (str (str/join (repeat (+ max-size 2) " ")) line)))
                                 snippet-lines)]
          (clojure.string/join "\n" snippet-lines))))))

(defn right-pad [s n]
  (let [n (- n (count s))]
    (str s (str/join (repeat n " ")))))

(defn print-locals [locals]
  (let [max-name-length (reduce max 0 (map (comp count str)
                                           (keys locals)))
        max-name-length (+ max-name-length 2)]
    (binding [*print-length* 10
              *print-level* 2]
      (doseq [[k v] locals]
        (print (str (right-pad (str k ": ") max-name-length)))
        ;; print nil as nil
        (prn v)))))

(defn error-handler [^Exception e opts]
  (binding [*out* *err*]
    (let [d (ex-data e)
          cause-exit (some-> e ex-cause ex-data :exit)
          exit-code (or (:exit d) cause-exit)
          sci-error? (isa? (:type d) :sci/error)
          ex-name (when sci-error?
                    (some-> ^Throwable (ex-cause e)
                            .getClass .getName))
          stacktrace (some->
                      d :sci.impl/callstack
                      cs/stacktrace)]
      (if exit-code [nil exit-code]
          (do
            (ruler "Error")
            (println "Type:    " (or
                                  ex-name
                                  (.. e getClass getName)))
            (when-let [m (.getMessage e)]
              (println (str "Message:  " m)))
            (let [{:keys [:file :line :column]} d]
              (when line
                (println (str "Location: "
                              (when file (str file ":"))
                              line ":" column""))))
            (when-let [phase (cs/phase e stacktrace)]
              (println "Phase:   " phase))
            (println)
            (when-let [ec (when sci-error?
                            (error-context e opts))]
              (ruler "Context")
              (println ec)
              (println))
            (when-let [locals (not-empty (:locals d))]
              (ruler "Locals")
              (print-locals locals)
              (println))
            (when sci-error?
              (when-let
                  [st (let [st (with-out-str
                                 (when stacktrace
                                   (print-stacktrace stacktrace opts)))]
                        (when-not (str/blank? st) st))]
                (ruler "Stack trace")
                (println st)))
            (when (:debug opts)
              (ruler "Exception")
              (print-stack-trace e))
              (flush)
              [nil 1])))))
