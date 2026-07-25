#!/usr/bin/env bb

;; Starts an nREPL inside the sandbox for a working directory of choice.
;;
;;   .sbx/repl.clj                       ;; REPL for the current directory
;;   .sbx/repl.clj --root <dir>          ;; REPL for another checkout or worktree
;;   .sbx/repl.clj --root <dir> --port 7900
;;   .sbx/repl.clj --aliases :dev:test   ;; another project's aliases
;;
;; The sandbox is created once for the git project root. Worktrees live under
;; it, so they are already visible inside and need no sandbox of their own.
;; Both the sandbox and the REPL are only created when they are not there yet.

(ns repl
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def cli-spec
  {:root {:desc "Working directory for the REPL, defaults to the current one"}
   :port {:desc "nREPL port, defaults to a free one" :coerce :long}
   ;; :repl-clojure overrides a project alias's older clojure pin, for
   ;; clojure.repl.deps/add-libs. Injected below like :clear-main
   :aliases {:desc "Aliases to start the REPL with"
             :default ":test:repl-clojure:clear-main"}})

(defn- out [& args]
  (str/trim (:out (apply p/sh args))))

(defn- project-root
  "The main checkout, also for a worktree, since that is what gets mounted."
  [dir]
  (str (fs/parent (out {:dir (str dir)}
                       "git" "rev-parse" "--path-format=absolute" "--git-common-dir"))))

(defn- sandbox-exists? [nm]
  (contains? (set (str/split-lines (out "sbx" "ls" "-q"))) nm))

(defn- sandbox-name [project-root]
  (str (fs/file-name project-root) "-repl"))

(defn- live-ports
  "Ports of nREPLs actually running inside the sandbox. A published port still
  accepts connections after its REPL is gone, so the host cannot tell."
  [nm]
  (->> (out "sbx" "exec" nm "--" "sh" "-c"
            "ps ax | grep '[n]repl.cmdline' | grep -o -- '--port [0-9]*'")
       str/split-lines
       (keep #(some-> (re-find #"(\d+)" %) second parse-long))
       set))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn- running-port
  "The port of the REPL already serving `root`, if it is still up."
  [nm root]
  (let [f (fs/file root ".nrepl-port")]
    (when (fs/exists? f)
      (let [port (parse-long (str/trim (slurp f)))]
        (when (contains? (live-ports nm) port)
          port)))))

(defn- published-ports [nm]
  (->> (str/split-lines (out "sbx" "ports" nm))
       (drop 1)
       (keep #(some-> (re-find #"^\S+\s+(\d+)\s" %) second parse-long))
       distinct))

(defn- prune-ports!
  "Unpublishes ports of REPLs that are gone. sbx runs on the host, so the REPL
  cannot do this itself when it exits."
  [nm]
  (let [live (live-ports nm)]
    (doseq [port (remove live (published-ports nm))]
      (println "unpublishing dead port" port)
      (p/shell {:continue true} "sbx" "ports" nm "--unpublish" (str port ":" port)))))

(defn- start! [nm root port aliases]
  (let [;; nrepl writes .nrepl-port itself but never cleans it up, so remove it
        ;; when the REPL exits, whether it was killed or died. `sbx exec -d`
        ;; keeps the process alive detached: since sbx v0.35 a container stops
        ;; when its last attached exec session ends, which kills a setsid\'d
        ;; REPL along with it
        cmd (format (str "cd \"%s\" && "
                         "clojure -Sdeps \"{:deps {nrepl/nrepl {:mvn/version \\\"1.3.1\\\"} "
                         "io.github.tonsky/clj-reload {:mvn/version \\\"0.9.7\\\"}} "
                         ":aliases {:clear-main {:main-opts []} "
                         ":repl-clojure {:extra-deps {org.clojure/clojure {:mvn/version \\\"1.12.1\\\"}}}}}\" "
                         "-M\"%s\" -m nrepl.cmdline "
                         "--bind 0.0.0.0 --port %s > \"/tmp/nrepl-%s.log\" 2>&1; "
                         "rm -f \"%s/.nrepl-port\"")
                    root aliases port (fs/file-name root) root)]
    (p/shell "sbx" "exec" "-d" nm "--" "sh" "-c" cmd)))

(defn -main [& args]
  (let [{:keys [root port aliases]} (cli/parse-opts args {:spec cli-spec})
        root (str (fs/absolutize (or root (fs/cwd))))
        proot (project-root root)
        nm (sandbox-name proot)]
    (when-not (sandbox-exists? nm)
      (println "creating sandbox" nm "for" proot)
      ;; sbx create can report a non zero exit while the sandbox is there, so
      ;; let the listing decide whether it worked
      (p/shell {:continue true}
               "sbx" "create" "shell" "--name" nm
               "--kit" (str (fs/file proot ".sbx"))
               proot (str (fs/file (fs/home) ".m2")))
      (when-not (sandbox-exists? nm)
        (println "could not create sandbox" nm)
        (System/exit 1)))
    (prune-ports! nm)
    (if-let [up (running-port nm root)]
      (do (println "repl already running for" root "on port" up)
          (println up))
      (let [port (or port (free-port))]
        (println "starting repl for" root "on port" port)
        (p/shell "sbx" "ports" nm "--publish" (str port ":" port))
        (start! nm root port aliases)
        (loop [n 0]
          ;; the port file must be there too, else a rerun starts a second repl
          (cond (= port (running-port nm root))
                (do (println "ready") (println port))
                (> n 120) (do (println "repl did not come up, see /tmp/nrepl-"
                                       (fs/file-name root) ".log in the sandbox")
                              (System/exit 1))
                :else (do (Thread/sleep 1000) (recur (inc n)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
