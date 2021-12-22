(ns ruuter.core-test
  #?(:clj (:require [clojure.test :refer :all]
                    [ruuter.core :as ruuter]))
  #?(:cljs (:require [cljs.test :refer-macros [deftest testing is]]
                     [ruuter.core :as ruuter])))


(deftest path+uri->path-params-test
  (let [testfn #'ruuter/path+uri->path-params]
    (testing "No params returns an empty map"
      (is (= {}
             (testfn "/hello/world" "/hello/world"))))
    (testing "Having a param returns a map accordingly"
      (is (= {:who "world"}
             (testfn "/hello/:who" "/hello/world"))))
    (testing "Multiple params returns a map accordingly"
      (is (= {:who "world"
              :why "because"}
             (testfn "/hello/:who/:why" "/hello/world/because"))))
    (testing "Multiple params, but one is optional"
      (is (= {:who "world"}
             (testfn "/hello/:who/:why?" "/hello/world")))
      (is (= {:who "world"
              :why "because"}
             (testfn "/hello/:who/:why?" "/hello/world/because"))))))


(deftest match-route-test
  (let [testfn #'ruuter/match-route]
    (testing "Find a route that exists"
      (is (= {:path "/hello"
              :method :get
              :response {:status 200
                         :body "Hello."}}
             (testfn [{:path "/hello"
                       :method :get
                       :response {:status 200
                                  :body "Hello."}}] "/hello" :get))))
    (testing "No route found"
      (is (= nil
             (testfn [] "/hello" :get))))))


(deftest route+req->response-test
  (let [testfn #'ruuter/route+req->response]
    (testing "Returning a map when the response is a direct map"
      (= {:status 200
          :body "Hello."}
         (testfn {:path "/hello"
                  :response {:status 200
                             :body "Hello."}}
                 {:uri "/hello"})))
    (testing "Returning a map via a fn when the response is a fn"
      (= {:status 200
          :body "Hello, world."}
         (testfn {:path "/hello/:who"
                  :response (fn [req]
                              {:status 200
                               :body (str "Hello, " (:who (:params req)))})}
                 {:uri "/hello/world"})))
    (testing "Returning an error map when route is invalid"
      (= {:status 404
          :body "Not found."}
         (testfn nil {:uri "/hello"})))))
