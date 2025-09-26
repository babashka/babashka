(ns babashka.impl.clojure.stacktrace
  {:no-doc true}
  (:require [clojure.stacktrace :as stacktrace]
            [clojure.string :as str]
            [sci.core :as sci]))

(def sns (sci/create-ns 'clojure.stacktrace nil))

(defmacro wrap-out [f]
  `(fn [& ~'args]
     (binding [*out* @sci/out]
       (apply ~f ~'args))))

(defn new-var [var-sym f]
  (sci/new-var var-sym f {:ns sns}))

(defn right-pad [s n]
  (let [n (- n (count s))]
    (str s (str/join (repeat n " ")))))

(defn format-stacktrace [st]
  (let [st (force st)
        data (keep (fn [{:keys [:file :ns :line :column :sci/built-in
                                :local]
                         nom :name}]
                     (when (or line built-in)
                       {:name (str (if nom
                                     (str ns "/" nom)
                                     ns)
                                   (when local
                                     (str "#" local)))
                        :loc (str (or file
                                      (if built-in
                                        "<built-in>"
                                        "<expr>"))
                                  (when line
                                    (str ":" line ":" column)))}))
                   st)
        max-name (reduce max 0 (map (comp count :name) data))]
    (mapv (fn [{:keys [:name :loc]}]
            (str (right-pad name max-name) " - " loc))
          data)))

(defn print-throwable
  [^Throwable tr]
  (when tr
    (printf "%s: %s" (.getName (class tr)) (.getMessage tr))
    (when-let [info (ex-data tr)]
      (newline)
      (pr info))))

(defn print-stack-trace [e]
  (print-throwable (.getCause e))
  (newline)
  (->> e
       (sci/stacktrace)
       (format-stacktrace)
       (run! println)))

(defn print-cause-trace
  ([tr] (print-cause-trace tr nil))
  ([^Throwable tr n]
   (print-stack-trace tr)
   (when-let [cause (.getCause tr)]
     (print "Caused by: ")
     (recur cause n))))

(def stacktrace-namespace
  {'root-cause (sci/copy-var stacktrace/root-cause sns)
   'print-trace-element (new-var 'print-trace-element (wrap-out stacktrace/print-trace-element))
   'print-throwable (new-var 'print-throwable (wrap-out stacktrace/print-throwable))
   ;; FIXME: expose print-stack-trace as well
   'print-stack-trace (new-var 'print-stack-trace (wrap-out stacktrace/print-stack-trace))
   ;; FIXME: should we make both regular and sci-aware stack printers available?
   'print-cause-trace (new-var 'print-cause-trace (wrap-out print-cause-trace))})
