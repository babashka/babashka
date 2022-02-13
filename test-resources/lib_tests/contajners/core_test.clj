(ns contajners.core-test
  (:require [clojure.test :as t]
            [contajners.core :as c]))

(t/deftest docker-tests
  (let [image  "busybox:musl"
        client (c/client {:engine   :docker
                          :version  "v1.41"
                          :category :images
                          :conn     {:uri "unix:///var/run/docker.sock"}})]
    (t/testing "pull an image"
      (c/invoke client
                {:op     :ImageCreate
                 :params {:fromImage image}})
      (let [images (c/invoke client {:op :ImageList})]
        (t/is (contains? (->> images
                              (mapcat :RepoTags)
                              (into #{}))
                         image)))
      (c/invoke client
                {:op     :ImageDelete
                 :params {:name image}}))))
