(ns babashka.impl.nrepl.java
  "Info and eldoc for Java classes and members, from reflection."
  {:no-doc true}
  (:require
   [clojure.reflect :as reflect]
   [clojure.string :as str]
   [sci.core :as sci]))

(set! *warn-on-reflection* true)

(defn- resolve-class
  "Returns the class class-str resolves to in ns-str, or nil."
  ^Class [ctx ns-str class-str]
  (let [c (try (sci/eval-form ctx (list 'clojure.core/ns-resolve
                                        (list 'quote (symbol (or ns-str "user")))
                                        (list 'quote (symbol class-str))))
               (catch Exception _ nil))]
    (when (instance? Class c) c)))

(defn- imported-classes [ctx ns-str]
  (try (filter #(instance? Class %)
               (sci/eval-form ctx (list 'clojure.core/vals
                                        (list 'clojure.core/ns-imports
                                              (list 'quote (symbol (or ns-str "user")))))))
       (catch Exception _ nil)))

(defn- type-str [t]
  (some-> t str (str/replace "<>" "[]")))

(defn- javadoc-url
  ([^Class c]
   (let [cname (.getName c)
         path (str (-> cname (str/replace "." "/") (str/replace "$" ".")) ".html")
         module (.getName (.getModule c))
         path (if module (str module "/" path) path)]
     (if (some #(str/starts-with? cname %) ["java." "javax." "jdk."])
       (str "https://docs.oracle.com/en/java/javase/"
            (System/getProperty "java.specification.version")
            "/docs/api/" path)
       path)))
  ([c member argtypes]
   (str (javadoc-url c) "#" member
        (when argtypes (str "(" (str/join "," argtypes) ")")))))

(defn- modifiers [flags]
  (mapv name (sort flags)))

(defn- members-named
  "Returns the methods and fields named member of c, or of its nearest
  superclass that has any."
  [^Class c member]
  (loop [^Class k c]
    (when k
      (let [ms (filter (fn [m]
                         (and (or (instance? clojure.reflect.Method m)
                                  (instance? clojure.reflect.Field m))
                              (= member (str (:name m)))
                              (not (contains? (:flags m) :synthetic))
                              (not (contains? (:flags m) :bridge))))
                       (:members (reflect/reflect k)))]
        (if (seq ms)
          ms
          (recur (.getSuperclass k)))))))

(defn- member-info
  "Returns the info of member of c, or nil if c has no such method or field."
  [^Class c member]
  (when-let [ms (seq (sort-by (juxt (comp count :parameter-types) (comp str :parameter-types))
                              (members-named c member)))]
    (let [m (first ms)
          static? (contains? (:flags m) :static)
          info {:class (.getName c)
                :member member
                :modifiers (modifiers (:flags m))}]
      (if (instance? clojure.reflect.Field m)
        (assoc info
               :type (type-str (:type m))
               :javadoc (javadoc-url c member nil))
        (let [argtypes (mapv type-str (:parameter-types m))
              arglists (mapv (fn [m]
                               (cond->> (mapv type-str (:parameter-types m))
                                 (not static?) (into ["this"])))
                             ms)]
          (assoc info
                 :arglists arglists
                 :arglists-str (str/join "\n" (map #(str "[" (str/join " " %) "]") arglists))
                 :argtypes argtypes
                 :returns (type-str (:return-type m))
                 :throws (mapv type-str (:exception-types m))
                 :javadoc (javadoc-url c member argtypes)))))))

(defn- class-info [^Class c]
  (let [cname (.getName c)
        idx (str/last-index-of cname ".")]
    (cond-> {:class cname
             :name (.getSimpleName c)
             :interfaces (mapv #(.getName ^Class %) (.getInterfaces c))
             :modifiers (modifiers (:flags (reflect/reflect c)))
             :javadoc (javadoc-url c)}
      idx (assoc :package (subs cname 0 idx))
      (.getSuperclass c) (assoc :super (.getName (.getSuperclass c))))))

(defn- instance-member-info
  "Returns the info of the instance member of the classes imported in ns-str,
  or {:candidates {class info}} if several classes have one."
  [ctx ns-str member]
  (let [infos (->> (imported-classes ctx ns-str)
                   (keep #(member-info % member))
                   (remove #(some #{"static"} (:modifiers %)))
                   distinct)]
    (case (count infos)
      0 nil
      1 (first infos)
      {:candidates (zipmap (map :class infos) infos)})))

(defn info
  "Returns the info of the Java class or member that sym, or class and
  member, name in ns, or nil if they name none.
  Arglists are in :arglists and, as a string, in :arglists-str."
  [ctx {:keys [ns sym class member]}]
  (let [sym (not-empty sym)]
    (if (seq class)
      (when-let [c (resolve-class ctx ns class)]
        (when-let [member (or (not-empty member)
                              (some-> sym (str/replace #"^\." "")))]
          (member-info c member)))
      (when sym
        (or (when-let [[_ cname] (re-matches #"(.+)\." sym)]
              (some-> (resolve-class ctx ns cname) class-info))
            (some-> (resolve-class ctx ns sym) class-info)
            (when-let [[_ member] (re-matches #"\.(.+)" sym)]
              (instance-member-info ctx ns member))
            (when-let [[_ cname member] (re-matches #"(.+)/\.?([^/]+)" sym)]
              (some-> (resolve-class ctx ns cname) (member-info member))))))))

(defn eldoc
  "Returns the eldoc reply for info, as info returns it."
  [{:keys [candidates] :as info}]
  (let [arglists (if candidates
                   (->> (vals candidates) (mapcat :arglists) distinct (sort-by count) vec)
                   (:arglists info))]
    (merge {:class (if candidates (vec (keys candidates)) [(:class info)])}
           (cond (:member info) {:member (:member info)}
                 candidates {:member (:member (first (vals candidates)))}
                 :else {:name (:name info)})
           (if (seq arglists)
             {:eldoc arglists :type "function"}
             {:type "variable"}))))

(defn info-reply
  "Returns the info reply for info, as info returns it."
  [info]
  (cond-> (dissoc info :arglists)
    (:candidates info) (update :candidates update-vals #(dissoc % :arglists))))
