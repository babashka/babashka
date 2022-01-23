#!/usr/bin/env bb
;; Source: https://github.com/prestancedesign/babashka-htmx-todoapp

(require '[org.httpkit.server :as srv]
         '[clojure.java.browse :as browse]
         '[clojure.core.match :refer [match]]
         '[clojure.pprint :refer [cl-format]]
         '[clojure.string :as str]
         '[hiccup.core :as h])

(import '[java.net URLDecoder])

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def port 3000)

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mimic DB (in-memory)
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def todos (atom (sorted-map 1 {:id 1 :name "Taste htmx with Babashka" :done true}
                             2 {:id 2 :name "Buy a unicorn" :done false})))

(def todos-id (atom (count @todos)))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; "DB" queries
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-todo! [name]
  (let [id (swap! todos-id inc)]
    (swap! todos assoc id {:id id :name name :done false})))

(defn toggle-todo! [id]
  (swap! todos update-in [(Integer. id) :done] not))

(defn remove-todo! [id]
  (swap! todos dissoc (Integer. id)))

(defn filtered-todo [filter-name todos]
  (case filter-name
    "active" (remove #(:done (val %)) todos)
    "completed" (filter #(:done (val %)) todos)
    "all" todos
    todos))

(defn get-items-left []
  (count (remove #(:done (val %)) @todos)))

(defn todos-completed []
  (count (filter #(:done (val %)) @todos)))

(defn remove-all-completed-todo []
  (reset! todos (into {} (remove #(:done (val %)) @todos))))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Template and components
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn todo-item [{:keys [id name done]}]
  [:li {:id (str "todo-" id)
        :class (when done "completed")}
   [:div.view
    [:input.toggle {:hx-patch (str "/todos/" id)
                    :type "checkbox"
                    :checked done
                    :hx-target (str "#todo-" id)
                    :hx-swap "outerHTML"}]
    [:label {:hx-get (str "/todos/edit/" id)
             :hx-target (str "#todo-" id)
             :hx-swap "outerHTML"} name]
    [:button.destroy {:hx-delete (str "/todos/" id)
                      :_ (str "on htmx:afterOnLoad remove #todo-" id)}]]])

(defn todo-list [todos]
  (for [todo todos]
    (todo-item (val todo))))

(defn todo-edit [id name]
  [:form {:hx-post (str "/todos/update/" id)}
   [:input.edit {:type "text"
                 :name "name"
                 :value name}]])

(defn item-count []
  (let [items-left (get-items-left)]
    [:span#todo-count.todo-count {:hx-swap-oob "true"}
     [:strong items-left] (cl-format nil " item~p " items-left) "left"]))

(defn todo-filters [filter]
  [:ul#filters.filters {:hx-swap-oob "true"}
   [:li [:a {:hx-get "/?filter=all"
             :hx-push-url "true"
             :hx-target "#todo-list"
             :class (when (= filter "all") "selected")} "All"]]
   [:li [:a {:hx-get "/?filter=active"
             :hx-push-url "true"
             :hx-target "#todo-list"
             :class (when (= filter "active") "selected")} "Active"]]
   [:li [:a {:hx-get "/?filter=completed"
             :hx-push-url "true"
             :hx-target "#todo-list"
             :class (when (= filter "completed") "selected")} "Completed"]]])

(defn clear-completed-button []
  [:button#clear-completed.clear-completed
   {:hx-delete "/todos"
    :hx-target "#todo-list"
    :hx-swap-oob "true"
    :hx-push-url "/"
    :class (when-not (pos? (todos-completed)) "hidden")}
   "Clear completed"])

(defn template [filter]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Htmx + Babashka"]
     [:link {:href "https://unpkg.com/todomvc-app-css@2.4.1/index.css" :rel "stylesheet"}]
     [:script {:src "https://unpkg.com/htmx.org@1.5.0/dist/htmx.min.js" :defer true}]
     [:script {:src "https://unpkg.com/hyperscript.org@0.8.1/dist/_hyperscript.min.js" :defer true}]]
    [:body
     [:section.todoapp
      [:header.header
       [:h1 "todos"]
       [:form
        {:hx-post "/todos"
         :hx-target "#todo-list"
         :hx-swap "beforeend"
         :_ "on htmx:afterOnLoad set #txtTodo.value to ''"}
        [:input#txtTodo.new-todo
         {:name "todo"
          :placeholder "What needs to be done?"
          :autofocus ""}]]]
      [:section.main
       [:input#toggle-all.toggle-all {:type "checkbox"}]
       [:label {:for "toggle-all"} "Mark all as complete"]]
      [:ul#todo-list.todo-list
       (todo-list (filtered-todo filter @todos))]
      [:footer.footer
       (item-count)
       (todo-filters filter)
       (clear-completed-button)]]
     [:footer.info
      [:p "Click to edit a todo"]
      [:p "Created by "
       [:a {:href "https://twitter.com/PrestanceDesign"} "Michaël Sλlihi"]]
      [:p "Part of "
       [:a {:href "http://todomvc.com"} "TodoMVC"]]]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-body [body]
  (-> body
      slurp
      (str/split #"=")
      second
      URLDecoder/decode))

(defn parse-query-string [query-string]
  (when query-string
    (-> query-string
        (str/split #"=")
        second)))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn app-index [{:keys [query-string headers]}]
  (let [filter (parse-query-string query-string)
        ajax-request? (get headers "hx-request")]
    (if (and filter ajax-request?)
      (h/html (todo-list (filtered-todo filter @todos))
              (todo-filters filter))
      (template filter))))

(defn add-item [{body :body}]
  (let [name (parse-body body)
        todo (add-todo! name)]
    (h/html (todo-item (val (last todo)))
            (item-count))))

(defn edit-item [id]
  (let [{:keys [id name]} (get @todos (Integer. id))]
    (h/html (todo-edit id name))))

(defn update-item [{body :body} id]
  (let [name (parse-body body)
        todo (swap! todos assoc-in [(Integer. id) :name] name)]
    (h/html (todo-item (get todo (Integer. id))))))

(defn patch-item [id]
  (let [todo (toggle-todo! id)]
    (h/html (todo-item (get todo (Integer. id)))
            (item-count)
            (clear-completed-button))))

(defn delete-item [id]
  (remove-todo! id)
  (h/html (item-count)))

(defn clear-completed []
  (remove-all-completed-todo)
  (h/html (todo-list @todos)
          (item-count)
          (clear-completed-button)))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [{:keys [request-method uri] :as req}]
  (let [path (vec (rest (str/split uri #"/")))]
    (match [request-method path]
           [:get []] {:body (app-index req)}
           [:get ["todos" "edit" id]] {:body (edit-item id)}
           [:post ["todos"]] {:body (add-item req)}
           [:post ["todos" "update" id]] {:body (update-item req id)}
           [:patch ["todos" id]] {:body (patch-item id)}
           [:delete ["todos" id]] {:body (delete-item id)}
           [:delete ["todos"]] {:body (clear-completed)}
           :else {:status 404 :body "Error 404: Page not found"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;

(when (= *file* (System/getProperty "babashka.file"))
  (let [url (str "http://localhost:" port "/")]
    (srv/run-server #'routes {:port port})
    (println "serving" url)
    (browse/browse-url url)
    @(promise)))
