(ns portal.bench
  #?(:cljs (:refer-clojure :exclude [simple-benchmark]))
  #?(:cljs (:require-macros portal.bench)))

(defn now []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defmacro simple-benchmark
  [bindings expr iterations & {:keys [print-fn] :or {print-fn 'println}}]
  (let [expr-str (pr-str expr)]
    `(let ~bindings
       (dotimes [_# ~iterations] ~expr)
       (let [start#   (now)
             ret#     (dotimes [_# ~iterations] ~expr)
             end#     (now)
             elapsed# (- end# start#)]
         (~print-fn (str ~iterations " runs, " elapsed# " msecs, " ~expr-str))))))
