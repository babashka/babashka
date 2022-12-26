(ns babashka.reify-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb (when (some? input) (str input)) (map str args))))

(deftest file-filter-test
  (is (true? (bb nil "
(def filter-obj (reify java.io.FileFilter
                  (accept [this f] (prn (.getPath f)) true)))
(def filename-filter-obj
                 (reify java.io.FilenameFilter
                  (accept [this f name] (prn name) true)))
(def s1 (with-out-str (.listFiles (clojure.java.io/file \".\") filter-obj)))
(def s2 (with-out-str (.listFiles (clojure.java.io/file \".\") filename-filter-obj)))
(and (pos? (count s1)) (pos? (count s2)))"))))

(deftest reify-multiple-arities-test
  (testing "ILookup"
    (is (= ["->:foo" 10]
           (bb nil "
(def m (reify clojure.lang.ILookup
  (valAt [this x] (str \"->\" x))
  (valAt [this x y] y)))
[(:foo m) (:foo m 10)]"))))
  (testing "IFn"
    (is (= [:yo :three :six :twelve :eighteen :nineteen 19]
           (bb nil "
(def m (reify clojure.lang.IFn
  (invoke [this] :yo)
  (invoke [this _ _ _] :three)
  (invoke [this _ _ _ _ _ _] :six)
  (invoke [this _ _ _ _ _ _ _ _ _ _ _ _] :twelve)
  (invoke [this _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _] :eighteen)
  (invoke [this _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _] :nineteen)
  (applyTo [this args] (last args))))
[
(m)
(m 1 2 3)
(m 1 2 3 4 5 6)
(m 1 2 3 4 5 6 1 2 3 4 5 6)
(m 1 2 3 4 5 6 1 2 3 4 5 6 1 2 3 4 5 6)
(m 1 2 3 4 5 6 1 2 3 4 5 6 1 2 3 4 5 6 1)
(apply m (range 20))
]")))))

(deftest reify-object
  (testing "empty methods"
    (is (str/starts-with?
         (bb nil "
(str (reify Object))")
         "babashka.impl.reify")))
  (testing "toString"
    (is (= ":foo"
           (bb nil "
(def m (reify Object
  (toString [_] (str :foo))))
(str m)
"))))
  (testing "toString + protocol"
    (is (= ":dude1:dude2"
           (bb nil "
(defprotocol Dude (dude [_])) (def obj (reify Object (toString [_] (str :dude1)) Dude (dude [_] :dude2))) (str (str obj) (dude obj))
"))))
  (testing "Hashcode still works when only overriding toString"
    (is (number?
         (bb nil "
(def m (reify Object
  (toString [_] (str :foo))))
(hash m)
")))))

(deftest reify-file-visitor-test
  (let [prog '(do (ns dude
                    (:import
                     [java.io File]
                     [java.nio.file FileSystems FileVisitor FileVisitResult Files Path])
                    (:gen-class))

                  (defn match-paths
                    "Match glob to paths under root and return a collection of Path objects"
                    [^File root glob]
                    (let [root-path (.toPath root)
                          matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob))
                          paths (volatile! [])
                          visitor (reify FileVisitor
                                    (visitFile [_ path attrs]
                                      (when (.matches matcher (.relativize root-path ^Path path))
                                        (vswap! paths conj path))
                                      FileVisitResult/CONTINUE)
                                    (visitFileFailed [_ path ex]
                                      FileVisitResult/CONTINUE)
                                    (preVisitDirectory [_ _ _] FileVisitResult/CONTINUE)
                                    (postVisitDirectory [_ _ _] FileVisitResult/CONTINUE))]
                      (Files/walkFileTree root-path visitor)
                      @paths))

                  [(count (match-paths (File. ".") "**"))
                   ;; visitFileFailed is implemented
                   (count (match-paths (File. "xxx") "**"))])
        [x y] (bb nil prog)]
    (is (pos? x))
    (is (zero? y))))

(deftest reify-default-method-test
  (let [prog '(do (def iter (let [coll [:a :b :c] idx (volatile! -1)]
                              (reify java.util.Iterator (hasNext [_] (< @idx 2))
                                (next [_] (nth coll (vswap! idx inc))))))
                  (def res (volatile! []))
                  (vswap! res conj (.hasNext iter))
                  (vswap! res conj (.next iter))
                  (.forEachRemaining
                   iter (reify java.util.function.Consumer (accept [_ x] (vswap! res conj x))))
                  (= [true :a :b :c] @res))]
    (is (true? (bb nil prog)))))

(deftest reify-multiple-interfaces-test
  (testing "throws exception"
    (is (thrown?
         clojure.lang.ExceptionInfo
         (bb nil "
(reify
  java.lang.Object (toString [_] \"foo\")
  clojure.lang.Seqable (seq [_] '(1 2 3)))")))))

(deftest reify-runnable-and-garbage-collection-test
  (is (bb nil "
(def cleaner (java.lang.ref.Cleaner/create))
(def deleted? (atom false))
(defn make-cleanable-ref []
  (let [obj (Object.)]
    (.register cleaner obj
      (reify java.lang.Runnable
        (run [_]
          (reset! deleted? true))))
    nil))
(defn force-gc []
  (let [t (atom (Object.))
        wr (java.lang.ref.WeakReference. @t)]
    (reset! t nil)
    (while (or (.get wr)
               (not @deleted?))
      (System/gc)
      (System/runFinalization))))
(make-cleanable-ref)
(force-gc)
@deleted?
")))
