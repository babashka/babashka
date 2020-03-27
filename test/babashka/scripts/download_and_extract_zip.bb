(require '[clojure.java.io :as io] '[clojure.java.shell :refer [sh]] '[clojure.string :as str])
(import '[java.net URL HttpURLConnection])

(set! *warn-on-reflection* true)

(let [os-name (System/getProperty "os.name")
      os-name (str/lower-case os-name)
      os (cond (str/includes? os-name "nix") "linux"
               (str/includes? os-name "mac") "macos"
               (str/includes? os-name "win") "windows")
      tmp-dir (System/getProperty "java.io.tmpdir")
      zip-file (io/file tmp-dir "bb-0.0.78.zip")
      source (URL. "https://github.com/borkdude/babashka/releases/download/v0.0.78/babashka-0.0.78-macos-amd64.zip")
      conn ^HttpURLConnection (.openConnection ^URL source)]
  (.connect conn)
  (with-open [is (.getInputStream conn)]
    (io/copy is zip-file))
  (let [bb-file (io/file tmp-dir "bb-extracted")
        fs (java.nio.file.FileSystems/newFileSystem (.toPath zip-file) nil)
        to-extract (.getPath fs "bb" (into-array String []))]
    (java.nio.file.Files/copy to-extract (.toPath bb-file)
                              ^"[Ljava.nio.file.CopyOption;"
                              (into-array java.nio.file.CopyOption []))
    (.setExecutable bb-file true)
    (let [out (:out (sh (.getPath bb-file) "(+ 1 2 3)"))]
      (.delete bb-file)
      (.delete zip-file)
      (println out))))



