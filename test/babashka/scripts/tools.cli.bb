(require '[clojure.tools.cli :refer [parse-opts]])

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]])

(defn -main [& args]
  (let [{:keys [:options :summary]} (parse-opts args cli-options)
        port (:port options)]
    (case port
      8080 {:result 8080}
      summary)))

(-main "-p" "8080")
