(ns babashka.release-artifact
  (:require [borkdude.gh-release-artifact :as ghr]
            [clojure.string :as str]))

(defn release [& args]
  (let [current-version (-> (slurp "resources/BABASHKA_VERSION")
                            str/trim)
        ght (System/getenv "GITHUB_TOKEN")
        file (first args)]
    (when ght
      (assert file "File name must be provided")
      (ghr/overwrite-asset {:org "babashka"
                            :repo "babashka"
                            :file file
                            :tag (str "vx" current-version)}))
    nil))
