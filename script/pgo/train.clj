;; Training run for the PGO profile. Run by script/pgo/refresh.
(require '[malli.core :as m] '[malli.generator :as mg] '[malli.error :as me] '[malli.transform :as mt] '[honey.sql :as sql] '[clojure.string :as str] '[clojure.set :as set] '[clojure.walk :as walk])
(def schema [:map [:id :int] [:name :string] [:tags [:vector :keyword]] [:addr [:map [:zip :string] [:city {:optional true} :string]]]])
(def v (m/validator schema))
(def data {:id 1 :name "a" :tags [:x :y] :addr {:zip "1" :city "A"}})
(dotimes [_ 500000] (v data))
(dotimes [_ 3000] (m/explain schema (assoc data :id "x")))
(dotimes [_ 3000] (m/decode schema {:id "1" :name "a" :tags ["x"] :addr {:zip "1"}} mt/string-transformer))
(dotimes [_ 300] (mg/generate schema))
(dotimes [_ 20000] (sql/format {:select [:a :b] :from [:t] :where [:and [:= :a 1] [:> :b 2]]}))
(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(fib 32)
(defrecord P [x y])
(defprotocol Q (q [_]))
(extend-type P Q (q [p] (:x p)))
(dotimes [_ 3] (reduce (fn [acc i] (assoc acc (mod i 1000) (q (->P i i)))) {} (range 1000000)))
(dotimes [_ 3] (->> (range 500000) (map inc) (filter even?) (partition-all 3) (map #(apply + %)) (reduce +)))
(dotimes [_ 200000] (let [{:keys [a b]} {:a 1 :b 2} [x & y] [a b 3]] (str x y)))
(dotimes [_ 50000] (str/join "," (map str/upper-case (str/split "a b c d e" #" "))))
(dotimes [_ 5000] (walk/postwalk identity {:a [1 2 {:b 3}]}))
(dotimes [_ 200] (eval '(fn [x] (let [y (inc x)] (if (pos? y) (mapv inc [y]) nil)))))
(dotimes [_ 100000] (try (throw (ex-info "x" {})) (catch Exception e (ex-data e))))
(dotimes [_ 100000] (let [a (atom 0)] (swap! a inc) (binding [*print-length* 1] @a)))
(dotimes [_ 30000] (pr-str {:a 1 :b [1 2 3]}) (read-string "{:a 1}"))
(dotimes [i 300000] (.substring (str "abc" i) 1) (Math/abs (long i)))
(doseq [i (range 3000000)] (when (zero? (rem i 2)) (inc i)))
(require '[aero.core] '[medley.core :as medley] '[camel-snake-kebab.core :as csk]
         '[loom.graph :as lg] '[loom.alg :as la]
         '[babashka.fs :as fs] '[babashka.http-client :as http] '[babashka.process :as p]
         '[babashka.tasks] '[cheshire.core :as json] '[clj-yaml.core :as yaml]
         '[clojure.data.csv :as csv] '[clojure.data.xml :as xml] '[clojure.edn :as edn]
         '[clojure.java.io :as io] '[clojure.test :as t] '[clojure.tools.cli :as cli]
         '[cognitect.transit :as transit] '[hiccup2.core :as h] '[org.httpkit.client :as hk]
         '[org.httpkit.server :as srv] '[rewrite-clj.zip :as z] '[selmer.parser :as selmer])
(import '[java.io ByteArrayInputStream ByteArrayOutputStream StringReader StringWriter]
        '[java.security MessageDigest]
        '[java.time Duration Instant LocalDate ZoneId]
        '[java.time.format DateTimeFormatter]
        '[java.util Base64 UUID])
(def records (vec (for [i (range 2000)] {:id i :name (str "n" i) :tags ["a" "b"] :nested {:x 1.5 :y true}})))
(dotimes [_ 100] (json/parse-string (json/generate-string records) true))
(dotimes [_ 15] (yaml/parse-string (yaml/generate-string (take 300 records))))
(dotimes [_ 30] (let [o (ByteArrayOutputStream.)]
                  (transit/write (transit/writer o :json) records)
                  (transit/read (transit/reader (ByteArrayInputStream. (.toByteArray o)) :json))))
(dotimes [_ 10] (let [w (StringWriter.)]
                  (csv/write-csv w (for [i (range 20000)] [(str i) "a" "b,c"]))
                  (doall (csv/read-csv (StringReader. (str w))))))
(dotimes [_ 15] (xml/parse-str (xml/emit-str (xml/element :a {} (for [i (range 3000)] (xml/element :b {:i (str i)} "x"))))))
(dotimes [_ 500] (-> (z/of-string "(defn foo [x] (let [y 1] (+ x y)))") z/down z/right (z/edit str) z/root-string))
(dotimes [_ 3000] (selmer/render "Hello {{name}} {% for x in xs %}{{x}}{% endfor %}" {:name "a" :xs [1 2 3]}))
(dotimes [_ 5000] (str (h/html [:div {:class "a"} [:ul (for [i (range 5)] [:li i])]])))
(dotimes [_ 5000] (cli/parse-opts ["-a" "1" "b"] [["-a" "--aa X" "d"]]))
(let [digest (MessageDigest/getInstance "SHA-256") bytes (byte-array 65536)]
  (dotimes [_ 500] (.digest digest bytes))
  (dotimes [_ 5000] (.encodeToString (Base64/getEncoder) (byte-array 256))))
(dotimes [_ 50000] (re-seq #"\w+@\w+\.com" "foo bar baz@qux.com lorem ipsum a@b.com"))
(dotimes [_ 3] (sort (map str (range 300000))))
(dotimes [_ 200000]
  (let [instant (Instant/parse "2024-01-01T00:00:00Z")]
    (.plus instant (Duration/ofHours 1))
    (.atZone instant (ZoneId/of "UTC"))))
(dotimes [_ 50000]
  (.format (LocalDate/parse "2024-01-01") (DateTimeFormatter/ofPattern "yyyy/MM/dd"))
  (str (UUID/randomUUID)))
(dotimes [_ 20] (medley/map-vals inc (zipmap (range 10000) (range))))
(dotimes [_ 100000] (csk/->kebab-case "fooBarBaz"))
(let [graph (apply lg/digraph (for [i (range 2000)] [i (inc i)]))]
  (dotimes [_ 20] (la/bf-traverse graph 0)))
(fs/with-temp-dir [dir {}]
  (dotimes [i 300]
    (let [file (fs/file dir (str i ".txt"))]
      (spit file (str/join "\n" (range 100)))
      (with-open [r (io/reader file)] (doall (line-seq r)))
      (fs/copy file (fs/file dir (str i ".bak")))))
  (dotimes [_ 100] (doall (fs/glob dir "**.txt")))
  (dotimes [_ 100] (fs/list-dir dir) (fs/walk-file-tree dir {})))
(dotimes [_ 20000] (edn/read-string "{:a [1 2 #{3}] :b \"c\"}"))
(dotimes [_ 30] (:out (p/sh "ls")))
(let [port 18731
      url (str "http://localhost:" port)
      stop (srv/run-server (fn [_] {:status 200 :body "ok"}) {:port port})]
  (dotimes [_ 500] (http/get url))
  (dotimes [_ 1000] @(hk/get url))
  (stop))
(t/deftest passing-test (t/is (= 1 1)))
(dotimes [_ 200] (binding [t/*test-out* (java.io.StringWriter.)] (t/run-tests)))
(babashka.tasks/shell {:out :string} "ls")
(println :done)
(System/exit 0)
