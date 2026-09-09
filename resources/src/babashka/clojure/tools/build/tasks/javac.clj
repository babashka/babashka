(ns ^{:skip-wiki true}
  clojure.tools.build.tasks.javac
  "BB-STAND-IN for tools.build's javac task, which compiles through the
  JDK's in-process compiler API. The image has no compiler, so this one
  spawns javac, from JAVA_HOME or the PATH, the way compile-clj spawns
  java. Same params as upstream: :basis :javac-opts :class-dir :src-dirs."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.tools.build.api :as api]
            [clojure.tools.build.util.file :as file])
  (:import [java.io File]))

(defn- javac-cmd []
  (let [exe (if (fs/windows?) "javac.exe" "javac")
        from-home (some-> (System/getenv "JAVA_HOME") (fs/file "bin" exe))]
    (cond (and from-home (fs/exists? from-home)) (str from-home)
          (fs/which exe) exe
          :else (throw (ex-info "javac not found: set JAVA_HOME or put javac on the PATH" {})))))

(defn javac
  [{:keys [basis javac-opts class-dir src-dirs] :as _params}]
  (let [{:keys [libs]} basis]
    (when (seq src-dirs)
      (let [class-dir (file/ensure-dir (api/resolve-path class-dir))
            class-dir-path (.getPath ^File class-dir)
            classpath (str/join File/pathSeparator (conj (mapcat :paths (vals libs)) class-dir-path))
            java-files (mapcat #(file/collect-files (api/resolve-path %) :collect (file/suffixes ".java")) src-dirs)
            cmd (concat [(javac-cmd) "-classpath" classpath "-d" class-dir-path]
                        javac-opts
                        (map str java-files))
            {:keys [exit]} (process/shell {:continue true :cmd (vec cmd)})]
        (when-not (zero? exit)
          (throw (ex-info "Java compilation failed" {})))))))
