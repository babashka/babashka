(ns my.middleware)

(defn println-middleware [handler]
  (fn [request]
    (println (:op (:msg request)))
    (handler request)))
