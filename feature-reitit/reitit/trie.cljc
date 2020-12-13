(ns reitit.trie
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [reitit.exception :as ex])
  #?(:clj (:import [reitit Trie Trie$Match Trie$Matcher]
                   (java.net URLDecoder))))

(defn ^:no-doc into-set [x]
  (cond
    (or (set? x) (sequential? x)) (set x)
    (nil? x) #{}
    :else (conj #{} x)))

(defrecord Wild [value])
(defrecord CatchAll [value])
(defrecord Match [params data])
(defrecord Node [children wilds catch-all params data])

(defn wild? [x] (instance? Wild x))
(defn catch-all? [x] (instance? CatchAll x))

(defprotocol Matcher
  (match [this i max path])
  (view [this])
  (depth ^long [this])
  (length [this]))

(defprotocol TrieCompiler
  (data-matcher [this params data])
  (static-matcher [this path matcher])
  (wild-matcher [this key end matcher])
  (catch-all-matcher [this key params data])
  (linear-matcher [this matchers ordered?])
  (-pretty [this matcher])
  (-path-matcher [this matcher]))

(defn- assoc-param [match k v]
  (let [params (:params match)]
    (assoc match :params (assoc params k v))))

;; https://stackoverflow.com/questions/8033655/find-longest-common-prefix
(defn- common-prefix [s1 s2]
  (let [max (min (count s1) (count s2))]
    (loop [i 0]
      (cond
        ;; full match
        (> i max)
        (subs s1 0 max)
        ;; partial match
        (not= (get s1 i) (get s2 i))
        (if-not (zero? i) (subs s1 0 i))
        ;; recur
        :else (recur (inc i))))))

(defn- -keyword [s]
  (if-let [^long i (str/index-of s "/")]
    (keyword (subs s 0 i) (subs s (inc i)))
    (keyword s)))

(defn split-path [s {:keys [syntax] :or {syntax #{:bracket :colon}}}]
  (let [bracket? (-> syntax (into-set) :bracket)
        colon? (-> syntax (into-set) :colon)
        -static (fn [from to] (if-not (= from to) [(subs s from to)]))
        -wild (fn [^long from to] [(->Wild (-keyword (subs s (inc from) to)))])
        -catch-all (fn [^long from to] [(->CatchAll (keyword (subs s (inc from) to)))])]
    (loop [ss nil, from 0, to 0]
      (if (= to (count s))
        (concat ss (-static from to))
        (let [c (get s to)]
          (cond

            (and bracket? (= \{ c))
            (let [^long to' (or (str/index-of s "}" to) (ex/fail! ::unclosed-brackets {:path s}))]
              (if (= \* (get s (inc to)))
                (recur (concat ss (-static from to) (-catch-all (inc to) to')) (long (inc to')) (long (inc to')))
                (recur (concat ss (-static from to) (-wild to to')) (long (inc to')) (long (inc to')))))

            (and colon? (= \: c))
            (let [^long to' (or (str/index-of s "/" to) (count s))]
              (if (= 1 (- to' to))
                (recur ss from (inc to))
                (recur (concat ss (-static from to) (-wild to to')) (long to') (long to'))))

            (and colon? (= \* c))
            (let [to' (count s)]
              (recur (concat ss (-static from to) (-catch-all to to')) (long to') (long to')))

            :else
            (recur ss from (inc to))))))))

(defn join-path [xs]
  (reduce
    (fn [s x]
      (str s (cond
               (string? x) x
               (instance? Wild x) (str "{" (-> x :value str (subs 1)) "}")
               (instance? CatchAll x) (str "{*" (-> x :value str (subs 1)) "}"))))
    "" xs))

(defn normalize [s opts]
  (-> s (split-path opts) (join-path)))

;;
;; Conflict Resolution
;;

(defn- -slice-start [[p1 :as p1s] [p2 :as p2s]]
  (let [-split (fn [p]
                 (if-let [i (and p (str/index-of p "/"))]
                   [(subs p 0 i) (subs p i)]
                   [p]))
        -slash (fn [cp p]
                 (cond
                   (not (string? cp)) [cp]
                   (and (string? cp) (not= (count cp) (count p))) [(subs p (count cp))]
                   (and (string? p) (not cp)) (-split p)))
        -postcut (fn [[p :as pps]]
                   (let [^long i (and p (str/index-of p "/"))]
                     (if (and i (pos? i))
                       (concat [(subs p 0 i) (subs p i)] (rest pps))
                       pps)))
        -tailcut (fn [cp [p :as ps]] (concat (-slash cp p) (rest ps)))]
    (if (or (nil? p1) (nil? p2))
      [(-postcut p1s) (-postcut p2s)]
      (if-let [cp (and (string? p1) (string? p2) (common-prefix p1 p2))]
        [(-tailcut cp p1s) (-tailcut cp p2s)]
        [p1s p2s]))))

(defn- -slice-end [x xs]
  (let [i (if (string? x) (str/index-of x "/"))]
    (if (and (number? i) (pos? ^long i))
      (concat [(subs x i)] xs)
      xs)))

(defn conflicting-parts? [parts1 parts2]
  (let [[[s1 & ss1] [s2 & ss2]] (-slice-start parts1 parts2)]
    (cond
      (= s1 s2 nil) true
      (or (nil? s1) (nil? s2)) false
      (or (catch-all? s1) (catch-all? s2)) true
      (or (wild? s1) (wild? s2)) (recur (-slice-end s1 ss1) (-slice-end s2 ss2))
      (not= s1 s2) false
      :else (recur ss1 ss2))))

(defn conflicting-paths? [path1 path2 opts]
  (conflicting-parts? (split-path path1 opts) (split-path path2 opts)))

;;
;; Creating Tries
;;

(defn- -node [m]
  (map->Node (merge {:children {}, :wilds {}, :catch-all {}, :params {}} m)))

(defn- -insert [node [path & ps] fp params data]
  (let [node' (cond

                (nil? path)
                (assoc node :data data :params params)

                (instance? Wild path)
                (let [next (first ps)]
                  (if (or (instance? Wild next) (instance? CatchAll next))
                    (ex/fail! ::following-parameters {:path fp, :parameters (map :value [path next])})
                    (update-in node [:wilds path] (fn [n] (-insert (or n (-node {})) ps fp params data)))))

                (instance? CatchAll path)
                (assoc-in node [:catch-all path] (-node {:params params, :data data}))

                (str/blank? path)
                (-insert node ps fp params data)

                :else
                (or
                  (reduce
                    (fn [_ [p n]]
                      (if-let [cp (common-prefix p path)]
                        (if (= cp p)
                          ;; insert into child node
                          (let [n' (-insert n (conj ps (subs path (count p))) fp params data)]
                            (reduced (assoc-in node [:children p] n')))
                          ;; split child node
                          (let [rp (subs p (count cp))
                                rp' (subs path (count cp))
                                n' (-insert (-node {}) ps fp params data)
                                n'' (-insert (-node {:children {rp n, rp' n'}}) nil nil nil nil)]
                            (reduced (update node :children (fn [children]
                                                              (-> children
                                                                  (dissoc p)
                                                                  (assoc cp n'')))))))))
                    nil (:children node))
                  ;; new child node
                  (assoc-in node [:children path] (-insert (-node {}) ps fp params data))))]
    (if-let [child (get-in node' [:children ""])]
      ;; optimize by removing empty paths
      (-> (merge-with merge (dissoc node' :data) child)
          (update :children dissoc ""))
      node')))

(defn- decode [path start end percent?]
  (let [param (subs path start end)]
    (if percent?
      #?(:cljs (js/decodeURIComponent param)
         :clj  (URLDecoder/decode
                 (if (.contains ^String param "+")
                   (.replace ^String param "+" "%2B")
                   param)
                 "UTF-8"))
      param)))

;;
;; Compilers
;;

(defn clojure-trie-compiler []
  (reify
    TrieCompiler
    (data-matcher [_ params data]
      (let [match (->Match params data)]
        (reify Matcher
          (match [_ i max _]
            (if (= i max)
              match))
          (view [_] data)
          (depth [_] 1)
          (length [_]))))
    (static-matcher [_ path matcher]
      (let [size (count path)]
        (reify Matcher
          (match [_ i max p]
            (if-not (< ^long max (+ ^long i size))
              (loop [j 0]
                (if (= j size)
                  (match matcher (+ ^long i size) max p)
                  (if (= (get p (+ ^long i j)) (get path j))
                    (recur (inc j)))))))
          (view [_] [path (view matcher)])
          (depth [_] (inc (depth matcher)))
          (length [_] (count path)))))
    (wild-matcher [_ key end matcher]
      (reify Matcher
        (match [_ i max path]
          (if (and (< ^long i ^long max) (not= (get path i) end))
            (loop [percent? false, j ^long i]
              (if (= max j)
                (if-let [match (match matcher max max path)]
                  (assoc-param match key (decode path i max percent?)))
                (let [c ^char (get path j)]
                  (condp = c
                    end (if-let [match (match matcher j max path)]
                          (assoc-param match key (decode path i j percent?)))
                    \% (recur true (inc j))
                    (recur percent? (inc j))))))))
        (view [_] [key (view matcher)])
        (depth [_] (inc (depth matcher)))
        (length [_])))
    (catch-all-matcher [_ key params data]
      (let [match (->Match params data)]
        (reify Matcher
          (match [_ i max path]
            (if (<= ^long i ^long max) (assoc-param match key (decode path i max true))))
          (view [_] [key [data]])
          (depth [_] 1)
          (length [_]))))
    (linear-matcher [_ matchers ordered?]
      (let [matchers (vec (if ordered? matchers (reverse (sort-by (juxt depth length) matchers))))
            size (count matchers)]
        (reify Matcher
          (match [_ i max path]
            (loop [j 0]
              (if (< j size)
                (or (match (get matchers j) i max path)
                    (recur (inc j))))))
          (view [_] (mapv view matchers))
          (depth [_] (inc ^long (apply max 0 (map depth matchers))))
          (length [_]))))
    (-pretty [_ matcher]
      (view matcher))
    (-path-matcher [_ matcher]
      (fn [path]
        (if-let [match (match matcher 0 (count path) path)]
          (->Match (:params match) (:data match)))))))

#?(:clj
   (defn java-trie-compiler []
     (reify
       TrieCompiler
       (data-matcher [_ params data]
         (Trie/dataMatcher params data))
       (static-matcher [_ path matcher]
         (Trie/staticMatcher ^String path ^Trie$Matcher matcher))
       (wild-matcher [_ key end matcher]
         (Trie/wildMatcher key (if end (Character. end)) matcher))
       (catch-all-matcher [_ key params data]
         (Trie/catchAllMatcher key params data))
       (linear-matcher [_ matchers ordered?]
         (Trie/linearMatcher matchers ordered?))
       (-pretty [_ matcher]
         (-> matcher str read-string eval))
       (-path-matcher [_ matcher]
         (fn [path]
           (if-let [match ^Trie$Match (Trie/lookup ^Trie$Matcher matcher ^String path)]
             (->Match (.params match) (.data match))))))))

;;
;; Managing Tries
;;

(defn- map-parameters [keys]
  (zipmap keys (repeat nil)))

#?(:clj
   (def record-parameters
     "Memoized function to transform parameters into runtime generated Record."
     (memoize
       (fn [keys]
         (if (some qualified-keyword? keys)
           (map-parameters keys)
           (let [sym (gensym "PathParams")
                 ctor (symbol (str "map->" sym))]
             (binding [*ns* (find-ns 'user)]
               (eval `(do (defrecord ~sym ~(mapv (comp symbol name) keys)) (~ctor {}))))))))))

(defn insert
  "Returns a trie with routes added to it."
  ([routes]
   (insert nil routes))
  ([node routes]
   (reduce
     (fn [acc [p d]]
       (insert acc p d))
     node routes))
  ([node path data]
   (insert node path data nil))
  ([node path data {::keys [parameters] :or {parameters map-parameters} :as opts}]
   (let [parts (split-path path opts)
         params (parameters (->> parts (remove string?) (map :value)))]
     (-insert (or node (-node {})) (split-path path opts) path params data))))

(defn babashka-message [& _]
  (println "Reitit compiler not supported by Babashka"))

(def compiler babashka-message)

(def compile babashka-message)

#_(defn compiler
  "Returns a default [[TrieCompiler]]."
  []
  #?(:cljs (clojure-trie-compiler)
     :clj  (java-trie-compiler)))

#_(defn compile
  "Returns a compiled trie, to be used with [[pretty]] or [[path-matcher]]."
  ([options]
   (compile options (compiler)))
  ([options compiler]
   (compile options compiler []))
  ([{:keys [data params children wilds catch-all] :or {params {}}} compiler cp]
   (let [ends (fn [{:keys [children]}] (or (keys children) ["/"]))
         matchers (-> []
                      (cond-> data (conj (data-matcher compiler params data)))
                      (into (for [[p c] children] (static-matcher compiler p (compile c compiler (conj cp p)))))
                      (into
                        (for [[p c] wilds]
                          (let [pv (:value p)
                                ends (ends c)]
                            (if (next ends)
                              (ex/fail! ::multiple-terminators {:terminators ends, :path (join-path (conj cp p))})
                              (wild-matcher compiler pv (ffirst ends) (compile c compiler (conj cp pv)))))))
                      (into (for [[p c] catch-all] (catch-all-matcher compiler (:value p) params (:data c)))))]
     (cond
       (> (count matchers) 1) (linear-matcher compiler matchers false)
       (= (count matchers) 1) (first matchers)
       :else (data-matcher compiler {} nil)))))

(defn pretty
  "Returns a simplified EDN structure of a compiled trie for printing purposes."
  ([compiled-trie]
   (pretty compiled-trie (compiler)))
  ([compiled-trie compiler]
   (-pretty compiler compiled-trie)))

(defn path-matcher
  "Returns a function of `path -> Match` from a compiled trie."
  ([compiled-trie]
   (path-matcher compiled-trie (compiler)))
  ([compiled-trie compiler]
   (-path-matcher compiler compiled-trie)))

;;
;; spike
;;

(comment
  (->
    [["/v2/whoami" 1]
     ["/v2/users/:user-id/datasets" 2]
     ["/v2/public/projects/:project-id/datasets" 3]
     ["/v1/public/topics/:topic" 4]
     ["/v1/users/:user-id/orgs/:org-id" 5]
     ["/v1/search/topics/:term" 6]
     ["/v1/users/:user-id/invitations" 7]
     ["/v1/users/:user-id/topics" 9]
     ["/v1/users/:user-id/bookmarks/followers" 10]
     ["/v2/datasets/:dataset-id" 11]
     ["/v1/orgs/:org-id/usage-stats" 12]
     ["/v1/orgs/:org-id/devices/:client-id" 13]
     ["/v1/messages/user/:user-id" 14]
     ["/v1/users/:user-id/devices" 15]
     ["/v1/public/users/:user-id" 16]
     ["/v1/orgs/:org-id/errors" 17]
     ["/v1/public/orgs/:org-id" 18]
     ["/v1/orgs/:org-id/invitations" 19]
     ["/v1/users/:user-id/device-errors" 22]
     ["/v2/login" 23]
     ["/v1/users/:user-id/usage-stats" 24]
     ["/v2/users/:user-id/devices" 25]
     ["/v1/users/:user-id/claim-device/:client-id" 26]
     ["/v2/public/projects/:project-id" 27]
     ["/v2/public/datasets/:dataset-id" 28]
     ["/v2/users/:user-id/topics/bulk" 29]
     ["/v1/messages/device/:client-id" 30]
     ["/v1/users/:user-id/owned-orgs" 31]
     ["/v1/topics/:topic" 32]
     ["/v1/users/:user-id/bookmark/:topic" 33]
     ["/v1/orgs/:org-id/members/:user-id" 34]
     ["/v1/users/:user-id/devices/:client-id" 35]
     ["/v1/users/:user-id" 36]
     ["/v1/orgs/:org-id/devices" 37]
     ["/v1/orgs/:org-id/members" 38]
     ["/v2/orgs/:org-id/topics" 40]
     ["/v1/whoami" 41]
     ["/v1/orgs/:org-id" 42]
     ["/v1/users/:user-id/api-key" 43]
     ["/v2/schemas" 44]
     ["/v2/users/:user-id/topics" 45]
     ["/v1/orgs/:org-id/confirm-membership/:token" 46]
     ["/v2/topics/:topic" 47]
     ["/v1/messages/topic/:topic" 48]
     ["/v1/users/:user-id/devices/:client-id/reset-password" 49]
     ["/v2/topics" 50]
     ["/v1/login" 51]
     ["/v1/users/:user-id/orgs" 52]
     ["/v2/public/messages/dataset/:dataset-id" 53]
     ["/v1/topics" 54]
     ["/v1/orgs" 55]
     ["/v1/users/:user-id/bookmarks" 56]
     ["/v1/orgs/:org-id/topics" 57]]
    (insert)
    (compile)
    (pretty)))
