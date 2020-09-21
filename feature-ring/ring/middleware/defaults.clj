(ns ring.middleware.defaults
  "Middleware for providing a handler with sensible defaults."
  (:require [ring.middleware.x-headers :as x]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.default-charset :refer [wrap-default-charset]]
            [ring.middleware.absolute-redirects :refer [wrap-absolute-redirects]]
            [ring.middleware.ssl :refer [wrap-ssl-redirect wrap-hsts wrap-forwarded-scheme]]
            [ring.middleware.proxy-headers :refer [wrap-forwarded-remote-addr]]))

(def api-defaults
  "A default configuration for a HTTP API."
  {:params    {:urlencoded true
               :keywordize true}
   :responses {:not-modified-responses true
               :absolute-redirects     true
               :content-types          true
               :default-charset        "utf-8"}})

(def secure-api-defaults
  "A default configuration for a HTTP API that's accessed securely over HTTPS."
  (-> api-defaults
      (assoc-in [:security :ssl-redirect] true)
      (assoc-in [:security :hsts] true)))

(def site-defaults
  "A default configuration for a browser-accessible website, based on current
  best practice."
  {:params    {:urlencoded true
               :multipart  true
               :nested     true
               :keywordize true}
   :cookies   true
   :session   {:flash true
               :cookie-attrs {:http-only true, :same-site :strict}}
   :security  {:anti-forgery   true
               :xss-protection {:enable? true, :mode :block}
               :frame-options  :sameorigin
               :content-type-options :nosniff}
   :static    {:resources "public"}
   :responses {:not-modified-responses true
               :absolute-redirects     true
               :content-types          true
               :default-charset        "utf-8"}})

(def secure-site-defaults
  "A default configuration for a browser-accessible website that's accessed
  securely over HTTPS."
  (-> site-defaults
      (assoc-in [:session :cookie-attrs :secure] true)
      (assoc-in [:session :cookie-name] "secure-ring-session")
      (assoc-in [:security :ssl-redirect] true)
      (assoc-in [:security :hsts] true)))

(defn- wrap [handler middleware options]
  (if (true? options)
    (middleware handler)
    (if options
      (middleware handler options)
      handler)))

(defn- wrap-multi [handler middleware args]
  (wrap handler
        (fn [handler args]
          (if (coll? args)
            (reduce middleware handler args)
            (middleware handler args)))
        args))

(defn- wrap-xss-protection [handler options]
  (x/wrap-xss-protection handler (:enable? options true) (dissoc options :enable?)))

(defn- wrap-x-headers [handler options]
  (-> handler
      (wrap wrap-xss-protection         (:xss-protection options false))
      (wrap x/wrap-frame-options        (:frame-options options false))
      (wrap x/wrap-content-type-options (:content-type-options options false))))

(defn wrap-defaults
  "Wraps a handler in default Ring middleware, as specified by the supplied
  configuration map.

  See: api-defaults
       site-defaults
       secure-api-defaults
       secure-site-defaults"
  [handler config]
  (-> handler
      (wrap wrap-anti-forgery     (get-in config [:security :anti-forgery] false))
      (wrap wrap-flash            (get-in config [:session :flash] false))
      (wrap wrap-session          (:session config false))
      (wrap wrap-keyword-params   (get-in config [:params :keywordize] false))
      (wrap wrap-nested-params    (get-in config [:params :nested] false))
      (wrap wrap-multipart-params (get-in config [:params :multipart] false))
      (wrap wrap-params           (get-in config [:params :urlencoded] false))
      (wrap wrap-cookies          (get-in config [:cookies] false))
      (wrap wrap-absolute-redirects (get-in config [:responses :absolute-redirects] false))
      (wrap-multi #(wrap-resource %1 %2 config) (get-in config [:static :resources] false))
      (wrap-multi wrap-file       (get-in config [:static :files] false))
      (wrap wrap-content-type     (get-in config [:responses :content-types] false))
      (wrap wrap-default-charset  (get-in config [:responses :default-charset] false))
      (wrap wrap-not-modified     (get-in config [:responses :not-modified-responses] false))
      (wrap wrap-x-headers        (:security config))
      (wrap wrap-hsts             (get-in config [:security :hsts] false))
      (wrap wrap-ssl-redirect     (get-in config [:security :ssl-redirect] false))
      (wrap wrap-forwarded-scheme      (boolean (:proxy config)))
      (wrap wrap-forwarded-remote-addr (boolean (:proxy config)))))
