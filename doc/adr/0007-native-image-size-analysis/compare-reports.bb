#!/usr/bin/env bb

;; Usage:
;;   Compare two reports:
;;     bb compare-reports.bb report-before.json report-after.json
;;
;;   Count public methods on classes (to find likely culprits):
;;     bb compare-reports.bb --methods java.util.stream.Collectors java.lang.reflect.Constructor

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[babashka.process :as p]
         '[clojure.string :as str])

(defn read-report [path]
  (json/parse-string (slurp path) true))

(defn get-in* [m ks]
  (get-in m ks 0))

(defn compare-reports [before-path after-path]
  (let [before (read-report before-path)
        after (read-report after-path)]
    (doseq [[label ks]
            [["total_bytes"        [:image_details :total_bytes]]
             ["code_area"          [:image_details :code_area :bytes]]
             ["image_heap"         [:image_details :image_heap :bytes]]
             ["heap objects"       [:image_details :image_heap :objects :count]]
             ["compilation_units"  [:image_details :code_area :compilation_units]]
             ["types total"        [:analysis_results :types :total]]
             ["types reachable"    [:analysis_results :types :reachable]]
             ["types reflection"   [:analysis_results :types :reflection]]
             ["methods total"      [:analysis_results :methods :total]]
             ["methods reachable"  [:analysis_results :methods :reachable]]
             ["methods reflection" [:analysis_results :methods :reflection]]
             ["fields total"       [:analysis_results :fields :total]]
             ["fields reachable"   [:analysis_results :fields :reachable]]
             ["fields reflection"  [:analysis_results :fields :reflection]]]]
      (let [b (get-in* before ks)
            a (get-in* after ks)
            d (- a b)]
        (printf "%-22s %,12d  %,12d  %+,10d\n" label b a d)))))

(defn count-methods
  "Count public methods/fields per class using javap.
  Classes in :all get full reflection — the more methods, the bigger the impact."
  [class-names]
  (printf "%-50s %8s %8s %8s\n" "class" "methods" "fields" "ctors")
  (println (apply str (repeat 76 "-")))
  (doseq [cn class-names]
    (let [out (:out (p/shell {:out :string :err :string} "javap" "-public" cn))
          lines (str/split-lines out)
          methods (count (filter #(and (str/includes? % "(") (not (str/includes? % cn))) lines))
          fields (count (filter #(and (not (str/includes? % "("))
                                      (str/includes? % "public")
                                      (not (str/includes? % "class "))) lines))
          ctors (count (filter #(str/includes? % (last (str/split cn #"\."))) (filter #(str/includes? % "(") lines)))]
      (printf "%-50s %8d %8d %8d\n" cn methods fields (max 0 (dec ctors))))))

(let [args *command-line-args*]
  (if (= "--methods" (first args))
    (count-methods (rest args))
    (compare-reports (first args) (second args))))
