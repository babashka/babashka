#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(def reitit-sha "5349199ce4f6c11181ea8aba067404de827e449c")

(defn reitit-module [module]
  {:git/url "https://github.com/metosin/reitit"
   :git/sha reitit-sha
   :deps/root (str "modules/" module)
   :deps/manifest :deps})

(deps/add-deps
 {:deps {'metosin/reitit-core (reitit-module "reitit-core")
         'metosin/reitit-ring (reitit-module "reitit-ring")
         'metosin/reitit-middleware (reitit-module "reitit-middleware")
         'meta-merge/meta-merge {:mvn/version "1.0.0"}
         'ring/ring-core {:mvn/version "1.15.5"}}})

(ns reitit-httpkit
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]))

(def todos (atom (sorted-map 1 {:id 1 :title "Try reitit in bb" :done true}
                             2 {:id 2 :title "Write a router" :done false})))

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(def wrap-json-body
  {:name ::json-body
   :wrap (fn [handler]
           (fn [req]
             (let [ct (get-in req [:headers "content-type"] "")]
               (handler (cond-> req
                          (and (:body req) (str/starts-with? ct "application/json"))
                          (assoc :json-body (json/parse-string (slurp (:body req)) true)))))))})

(def wrap-log
  {:name ::log
   :wrap (fn [handler]
           (fn [req]
             (let [start (System/nanoTime)
                   resp (handler req)]
               (println (format "%s %s -> %s (%.2f ms)"
                                (str/upper-case (name (:request-method req)))
                                (:uri req)
                                (:status resp)
                                (/ (- (System/nanoTime) start) 1e6)))
               resp)))})

(defn todo-url [router id]
  (-> (r/match-by-name router ::todo {:id id}) r/match->path))

(defn list-todos [{:keys [query-params]}]
  (let [done (get query-params "done")
        xs (vals @todos)]
    (json-response 200 (if done
                         (filter #(= (parse-boolean done) (:done %)) xs)
                         xs))))

(defn create-todo [{:keys [json-body] ::r/keys [router]}]
  (if-let [title (:title json-body)]
    (let [id (inc (or (first (keys (rseq @todos))) 0))
          todo {:id id :title title :done false}]
      (swap! todos assoc id todo)
      (-> (json-response 201 todo)
          (assoc-in [:headers "Location"] (todo-url router id))))
    (json-response 400 {:error "title is required"})))

(defn with-todo [f]
  (fn [{:keys [path-params] :as req}]
    (let [id (parse-long (:id path-params))]
      (if-let [todo (get @todos id)]
        (f req todo)
        (json-response 404 {:error (str "no todo with id " (:id path-params))})))))

(def get-todo
  (with-todo (fn [_ todo] (json-response 200 todo))))

(def update-todo
  (with-todo (fn [{:keys [json-body]} {:keys [id] :as todo}]
               (let [todo (merge todo (select-keys json-body [:title :done]))]
                 (swap! todos assoc id todo)
                 (json-response 200 todo)))))

(def delete-todo
  (with-todo (fn [_ {:keys [id]}]
               (swap! todos dissoc id)
               {:status 204})))

(def router
  (ring/router
   [["/" {:get (fn [_] {:status 200
                         :headers {"Content-Type" "text/plain"}
                         :body "Hello from reitit in babashka! Try /api/todos\n"})}]
    ["/api" {:middleware [wrap-json-body]}
     ["/todos"
      ["" {:name ::todos
           :get list-todos
           :post create-todo}]
      ["/:id" {:name ::todo
               :get get-todo
               :put update-todo
               :delete delete-todo}]]]]
   {:data {:middleware [wrap-log parameters/parameters-middleware]}}))

(def app
  (ring/ring-handler
   router
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler
     {:not-found (constantly (json-response 404 {:error "not found"}))
      :method-not-allowed (constantly (json-response 405 {:error "method not allowed"}))}))))

(defn -main [& _]
  (let [port 8090]
    (server/run-server #'app {:port port})
    (println (str "Listening on http://localhost:" port))
    (println "Routes:")
    (doseq [[path data] (r/routes router)]
      (println " " path (->> (select-keys data [:get :post :put :delete])
                             keys
                             (map (comp str/upper-case name))
                             (str/join " "))))
    @(promise)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
