#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.pprint :refer [pprint]]
         '[clojure.string :as str]
         '[org.httpkit.server :as server])

(def debug? true)
(def user "admin")
(def password "admin")
(def base64 (-> (.getEncoder java.util.Base64)
                (.encodeToString (.getBytes (str user ":" password)))))

(def notes-file (io/file (System/getProperty "user.home") ".notes" "notes.txt"))
(def file-lock (Object.))

(defn write-note! [note]
  (locking file-lock
    (io/make-parents notes-file)
    (spit notes-file (str note "\n") :append true)))

;; hiccup-like
(defn html [v]
  (cond (vector? v)
        (let [tag (first v)
              attrs (second v)
              attrs (when (map? attrs) attrs)
              elts (if attrs (nnext v) (next v))
              tag-name (name tag)]
          (format "<%s%s>%s</%s>\n" tag-name (html attrs) (html elts) tag-name))
        (map? v)
        (str/join ""
                  (map (fn [[k v]]
                         (format " %s=\"%s\"" (name k) v)) v))
        (seq? v)
        (str/join " " (map html v))
        :else (str v)))

;; the home page
(defn home-response [session-id]
  {:status 200
   :headers {"Set-Cookie" (str "notes-id=" session-id)}
   :body (str
          "<!DOCTYPE html>\n"
          (html
           [:html
            [:head
             [:title "Notes"]]
            [:body
             [:h1 "Notes"]
             [:pre (when (.exists notes-file)
                     (slurp notes-file))]
             [:form {:action "/" :method "post"}
              [:input {:type "text" :name "note"}]
              [:input {:type "submit" :value "Submit"}]]]]))})

(def known-sessions
  (atom #{}))

(defn new-session! []
  (let [uuid (str (java.util.UUID/randomUUID))]
    (swap! known-sessions conj uuid)
    uuid))

(def authenticated-sessions
  (atom #{}))

(defn authenticate! [session-id headers]
  (or (contains? @authenticated-sessions session-id)
      (when (= (headers "authorization") (str "Basic " base64))
        (swap! authenticated-sessions conj session-id)
        true)))

(defn parse-session-id [cookie]
  (when cookie
    (when-let [notes-id (first (filter #(str/starts-with? % "notes-id")
                                       (str/split cookie #"; ")))]
      (str/replace notes-id "notes-id=" ""))))

(defn basic-auth-response [session-id]
  {:status 401
   :headers {"WWW-Authenticate" "Basic realm=\"notes\""
             "Set-Cookie" (str "notes-id=" session-id)}})

;; run the server
(defn handler [req]
  (when debug?
    (println "Request:")
    (pprint req))
  (let [body (some-> req :body slurp java.net.URLDecoder/decode)
        session-id (parse-session-id (get-in req [:headers "cookie"]))
        _ (when (and debug? body)
            (println "Request body:" body))
        response (cond
                   ;; if we didn't see this session before, we want the user to
                   ;; re-authenticate
                   (not (contains? @known-sessions session-id))
                   (basic-auth-response (new-session!))

                   (not (authenticate! session-id (:headers req)))
                   (basic-auth-response session-id)

                   :else (do (when-not (str/blank? body)
                               (let [note (str/replace body "note=" "")]
                                 (write-note! note)))
                             (home-response session-id)))]
    (when debug?
      (println "Response:")
      (pprint (dissoc response :body))
      (println))
    response))

(server/run-server handler {:port 8080})
(println "Server started on port 8080.")
@(promise) ;; wait until SIGINT
