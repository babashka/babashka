(ns babashka.impl.reify
  {:no-doc true}
  #_(:require [clojure.math.combinatorics :as combo]))

;; (defmacro gen-reify-combos [classes]
;;   (let [subsets (rest (combo/subsets classes))]
;;     (reduce (fn [opts classes]
;;               (assoc opts (set (map (fn [[class _]]
;;                                       (list 'quote class))
;;                                     classes))
;;                      ;; TODO: methods per class
;;                      `(fn [{:keys [:methods]}]
;;                         (reify
;;                           ~@(mapcat
;;                                 (fn [[clazz methods]]
;;                                   (cons clazz
;;                                         (map (fn [[meth args]]
;;                                                (list meth args
;;                                                      (list* (list 'get-in 'methods [(list 'quote clazz) (list 'quote meth)])
;;                                                             args)))
;;                                              methods)))
;;                               classes)))))
;;             {}
;;             subsets)))

;; (def combos (gen-reify-combos [[clojure.lang.IPending [[isRealized [this]]]]
;;                                [clojure.lang.IDeref [[deref [this]]]]]))

;; (def pending-and-deref
;;   (let [factory-fn (get combos '#{clojure.lang.IPending clojure.lang.IDeref})]
;;     (factory-fn {:methods {'clojure.lang.IPending {'isRealized (fn [_] false)}
;;                            'clojure.lang.IDeref {'deref (fn [_] :deref)}}})))

;; (prn @pending-and-deref)
;; (prn (realized? pending-and-deref))
;; (prn (instance? clojure.lang.IDeref pending-and-deref))
;; (prn (instance? clojure.lang.IPending pending-and-deref))

;; (def only-deref
;;   (let [factory-fn (get combos '#{clojure.lang.IDeref})]
;;     (factory-fn {:methods {'clojure.lang.IDeref {'deref (fn [_] :deref)}}})))

;; (prn @only-deref)
;; (prn (instance? clojure.lang.IDeref only-deref))
;; (prn (instance? clojure.lang.IPending only-deref))

(def reify-opts
  {'java.nio.file.FileVisitor
   (fn [{:keys [:methods]}]
     {:obj (reify java.nio.file.FileVisitor
             (preVisitDirectory [this p attrs]
               ((get methods 'preVisitDirectory) this p attrs))
             (postVisitDirectory [this p attrs]
               ((get methods 'postVisitDirectory) this p attrs))
             (visitFile [this p attrs]
               ((get methods 'visitFile) this p attrs)))})
   'java.io.FileFilter
   (fn [{:keys [:methods]}]
     {:obj (reify java.io.FileFilter
             (accept [this f]
               ((get methods 'accept) this f)))})
   'java.io.FilenameFilter
   (fn [{:keys [:methods]}]
     {:obj (reify java.io.FilenameFilter
             (accept [this f s]
               ((get methods 'accept) this f s)))})})
