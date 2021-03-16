(ns babashka.cli
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(declare resolve-task)

(defn parse-opts [options]
  (prn :options options)
  (let [opts (loop [options options
                    opts-map {}]
               (if options
                 (let [opt (first options)]
                   (case opt
                     ("--") (assoc opts-map :command-line-args (next options))
                     ("--clojure" ":clojure") (assoc opts-map :clojure true
                                          :opts (rest options))
                     ("--version" ":version") {:version true}
                     ("--help" "-h" "-?") {:help? true}
                     ("--verbose")(recur (next options)
                                         (assoc opts-map
                                                :verbose? true))
                     ("--describe" ":describe") (recur (next options)
                                           (assoc opts-map
                                                  :describe? true))
                     ("--stream") (recur (next options)
                                         (assoc opts-map
                                                :stream? true))
                     ("-i") (recur (next options)
                                   (assoc opts-map
                                          :shell-in true))
                     ("-I") (recur (next options)
                                   (assoc opts-map
                                          :edn-in true))
                     ("-o") (recur (next options)
                                   (assoc opts-map
                                          :shell-out true))
                     ("-O") (recur (next options)
                                   (assoc opts-map
                                          :edn-out true))
                     ("-io") (recur (next options)
                                    (assoc opts-map
                                           :shell-in true
                                           :shell-out true))
                     ("-iO") (recur (next options)
                                    (assoc opts-map
                                           :shell-in true
                                           :edn-out true))
                     ("-Io") (recur (next options)
                                    (assoc opts-map
                                           :edn-in true
                                           :shell-out true))
                     ("-IO") (recur (next options)
                                    (assoc opts-map
                                           :edn-in true
                                           :edn-out true))
                     ("--classpath", "-cp")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map :classpath (first options))))
                     ("--uberscript" ":uberscript")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :uberscript (first options))))
                     ("--uberjar" ":uberjar")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :uberjar (first options))))
                     ("-f" "--file")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :file (first options))))
                     ("--jar" "-jar")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :jar (first options))))
                     ("--repl" ":repl")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :repl true)))
                     ("--socket-repl" ":socket-repl")
                     (let [options (next options)
                           opt (first options)
                           opt (when (and opt (not (str/starts-with? opt "-")))
                                 opt)
                           options (if opt (next options)
                                       options)]
                       (recur options
                              (assoc opts-map
                                     :socket-repl (or opt "1666"))))
                     ("--nrepl-server" ":nrepl-server")
                     (let [options (next options)
                           opt (first options)
                           opt (when (and opt (not (str/starts-with? opt "-")))
                                 opt)
                           options (if opt (next options)
                                       options)]
                       (recur options
                              (assoc opts-map
                                     :nrepl (or opt "1667"))))
                     ("--eval", "-e")
                     (let [options (next options)]
                       (recur (next options)
                              (update opts-map :expressions (fnil conj []) (first options))))
                     ("--main", "-m")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map :main (first options))))
                     ;; fallback
                     (if (some opts-map [:file :jar :socket-repl :expressions :main])
                       (assoc opts-map
                              :command-line-args options)
                       (let [trimmed-opt (str/triml opt)
                             c (.charAt trimmed-opt 0)]
                         (case c
                           (\( \{ \[ \* \@ \#)
                           (-> opts-map
                               (update :expressions (fnil conj []) (first options))
                               (assoc :command-line-args (next options)))
                           (if (fs/exists? opt)
                             (assoc opts-map
                                    (if (str/ends-with? opt ".jar")
                                      :jar :file) opt
                                    :command-line-args (next options))
                             (if (str/starts-with? opt ":")
                               (resolve-task opt {:command-line-args (next options)})
                               (throw (Exception. (str "File does not exist: " opt))))))))))
                 opts-map))]
    opts))

(defn resolve-task [task {:keys [:command-line-args]}]
  (let [bb-edn-file (or (System/getenv "BABASHKA_EDN")
                        "bb.edn")]
    (if (fs/exists? bb-edn-file)
      (let [bb-edn (edn/read-string (slurp bb-edn-file))]
        (if-let [task (get-in bb-edn [:tasks (keyword (subs task 1))])]
          (let [cmd-line-args (get task :babashka/args)]
            ;; this is for invoking babashka itself with command-line-args
            (parse-opts (seq (concat cmd-line-args command-line-args))))
          (throw (Exception. (str "No such task: " task)))))
      (throw (Exception. (str "File does not exist: " task))))))
