(ns babashka.tasks-cli-big)

(defn c [{:keys [dispatch]}] (prn dispatch))

(defn root
  "Big tree"
  {:org.babashka/cli '{:cmd {kilo {:fn babashka.tasks-cli-big/c}
                             juliet {:fn babashka.tasks-cli-big/c}
                             india {:fn babashka.tasks-cli-big/c}
                             hotel {:fn babashka.tasks-cli-big/c}
                             golf {:fn babashka.tasks-cli-big/c}
                             foxtrot {:fn babashka.tasks-cli-big/c}
                             echo {:fn babashka.tasks-cli-big/c}
                             delta {:fn babashka.tasks-cli-big/c}
                             charlie {:fn babashka.tasks-cli-big/c}
                             bravo {:fn babashka.tasks-cli-big/c}
                             alpha {:fn babashka.tasks-cli-big/c}}}}
  [{:keys [opts]}]
  (prn opts))
