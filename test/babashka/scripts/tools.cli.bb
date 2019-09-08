(require '[clojure.tools.cli :refer [parse-opts]])
(:options (parse-opts ["-f" "README.md"] [["-f" "--file FILE" "file"]]))
