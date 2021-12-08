(ns reifyhealth.specmonstah.core-test
  (:require #?(:clj [clojure.test :refer [deftest is are use-fixtures testing]]
               :cljs [cljs.test :include-macros true :refer [deftest is are use-fixtures testing]])
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as gen :include-macros true]
            [reifyhealth.specmonstah.test-data :as td]
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]))

(use-fixtures :each td/test-fixture)
(use-fixtures :once (fn [t] (stest/instrument) (t)))

(defmacro is-graph=
  "Breaks graph equality test into comparisons on graph keys to
  pinpoint inequality more quickly"
  [g1 g2]
  (let [g1-sym 'returned
        g2-sym 'expected]
    `(let [~g1-sym ~g1
           ~g2-sym ~g2]
       (are [k] (= (k ~g1-sym) (k ~g2-sym))
         :nodeset
         :adj
         :in
         :attrs))))

(deftest test-relation-graph
  (is-graph= (sm/relation-graph td/schema)
             (lg/digraph [:project :todo-list]
                         [:project :user]
                         [:todo-list-watch :todo-list]
                         [:todo-list-watch :user]
                         [:todo :todo-list]
                         [:todo-list :user]
                         [:todo :user]
                         [:attachment :todo]
                         [:attachment :user])))

(defn strip-db
  [db]
  (dissoc db :relation-graph :types :type-order))

(deftest test-add-ents-empty
  (is-graph= (strip-db (sm/add-ents {:schema td/schema} {}))
             {:schema td/schema
              :data   (lg/digraph)}))

(deftest test-bound-relation-attr-name
  (is (= (sm/bound-relation-attr-name (sm/add-ents {:schema td/schema} {}) :tl-bound-p-0 :todo 1)
         :t-bound-p-1)))

(deftest test-add-ents-relationless-ent
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:user [[:u1]]}))
             (-> (lg/digraph [:user :u1])
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :u1 :type :ent)
                 (lat/add-attr :u1 :index 0)
                 (lat/add-attr :u1 :query-term [:u1])
                 (lat/add-attr :u1 :ent-type :user))))

(deftest test-add-ents-mult-relationless-ents
  (is-graph= (:data (strip-db (sm/add-ents {:schema td/schema} {:user [[3]]})))
             (-> (lg/digraph [:user :u0] [:user :u1] [:user :u2])
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :u0 :type :ent)
                 (lat/add-attr :u0 :index 0)
                 (lat/add-attr :u0 :query-term [3])
                 (lat/add-attr :u0 :ent-type :user)
                 
                 (lat/add-attr :u1 :type :ent)
                 (lat/add-attr :u1 :index 1)
                 (lat/add-attr :u1 :query-term [3])
                 (lat/add-attr :u1 :ent-type :user)
                 
                 (lat/add-attr :u2 :type :ent)
                 (lat/add-attr :u2 :index 2)
                 (lat/add-attr :u2 :query-term [3])
                 (lat/add-attr :u2 :ent-type :user))))

(deftest test-add-ents-one-level-relation
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list [[1]]}))
             (-> (lg/digraph [:user :u0] [:todo-list :tl0] [:tl0 :u0])
                 
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :u0 :type :ent)
                 (lat/add-attr :u0 :index 0)
                 (lat/add-attr :u0 :query-term [:_])
                 (lat/add-attr :u0 :ent-type :user)

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [1])
                 
                 (lat/add-attr :tl0 :u0 :relation-attrs #{:created-by-id :updated-by-id}))))

(deftest test-add-ents-one-level-relation-with-omit
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list [[1 {:refs {:created-by-id ::sm/omit
                                                                             :updated-by-id ::sm/omit}}]]}))
             (-> (lg/digraph [:todo-list :tl0])

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [1 {:refs {:created-by-id ::sm/omit
                                                           :updated-by-id ::sm/omit}}]))))

(deftest testadd-entsb-mult-ents-w-extended-query
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list [[2 {:refs {:created-by-id :bloop :updated-by-id :bloop}}]]}))
             (-> (lg/digraph [:user :bloop]
                             [:todo-list :tl0]
                             [:todo-list :tl1]
                             [:tl0 :bloop]
                             [:tl1 :bloop])
                 
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :bloop :type :ent)
                 (lat/add-attr :bloop :index 0)
                 (lat/add-attr :bloop :query-term [:_])
                 (lat/add-attr :bloop :ent-type :user)

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [2 {:refs {:created-by-id :bloop :updated-by-id :bloop}}])

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl1 :type :ent)
                 (lat/add-attr :tl1 :index 1)
                 (lat/add-attr :tl1 :ent-type :todo-list)
                 (lat/add-attr :tl1 :query-term [2 {:refs {:created-by-id :bloop :updated-by-id :bloop}}])

                 (lat/add-attr :tl0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                 (lat/add-attr :tl1 :bloop :relation-attrs #{:created-by-id :updated-by-id}))))

(deftest test-add-ents-one-level-relation-custom-related
  (is-graph= (:data (strip-db (sm/add-ents {:schema td/schema} {:todo-list [[:_ {:refs {:created-by-id :owner0
                                                                                        :updated-by-id :owner0}}]]})))
             (-> (lg/digraph [:user :owner0] [:todo-list :tl0] [:tl0 :owner0])
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :owner0 :type :ent)
                 (lat/add-attr :owner0 :index 0)
                 (lat/add-attr :owner0 :query-term [:_])
                 (lat/add-attr :owner0 :ent-type :user)
                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [:_ {:refs {:created-by-id :owner0
                                                            :updated-by-id :owner0}}])
                 (lat/add-attr :tl0 :owner0 :relation-attrs #{:updated-by-id :created-by-id}))))

(deftest testadd-entsb-two-level-coll-relation
  (testing "can specify how many ents to gen in a coll relationship"
    (is-graph= (:data (strip-db (sm/add-ents {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids 2}}]]})))
               (-> (lg/digraph [:user :u0]
                               [:todo-list :tl0] [:todo-list :tl1]  [:tl0 :u0] [:tl1 :u0]
                               [:project :p0] [:p0 :u0] [:p0 :tl0] [:p0 :tl1] [:p0 :u0])

                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :u0 :type :ent)
                   (lat/add-attr :u0 :index 0)
                   (lat/add-attr :u0 :query-term [:_])
                   (lat/add-attr :u0 :ent-type :user)
                   
                   (lat/add-attr :project :type :ent-type)
                   (lat/add-attr :p0 :type :ent)
                   (lat/add-attr :p0 :index 0)
                   (lat/add-attr :p0 :query-term [:_ {:refs {:todo-list-ids 2}}])
                   (lat/add-attr :p0 :ent-type :project)
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   
                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl0 :type :ent)
                   (lat/add-attr :tl0 :index 0)
                   (lat/add-attr :tl0 :ent-type :todo-list)
                   (lat/add-attr :tl0 :query-term [:_])

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl1 :type :ent)
                   (lat/add-attr :tl1 :index 1)
                   (lat/add-attr :tl1 :ent-type :todo-list)
                   (lat/add-attr :tl1 :query-term [:_])

                   (lat/add-attr :p0 :tl0 :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :tl1 :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :tl0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :tl1 :u0 :relation-attrs #{:created-by-id :updated-by-id})))))

(deftest test-add-ents-two-level-coll-relation-names
  (testing "can specify names in a coll relationship"
    (is-graph= (:data (strip-db (sm/add-ents {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids [:mario :luigi]}}]]})))
               (-> (lg/digraph [:user :u0]
                               [:todo-list :mario] [:todo-list :luigi]  [:mario :u0] [:luigi :u0]
                               [:project :p0] [:p0 :u0] [:p0 :mario] [:p0 :luigi] [:p0 :u0])

                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :u0 :type :ent)
                   (lat/add-attr :u0 :index 0)
                   (lat/add-attr :u0 :query-term [:_])
                   (lat/add-attr :u0 :ent-type :user)
                   
                   (lat/add-attr :project :type :ent-type)
                   (lat/add-attr :p0 :type :ent)
                   (lat/add-attr :p0 :index 0)
                   (lat/add-attr :p0 :query-term [:_ {:refs {:todo-list-ids [:mario :luigi]}}])
                   (lat/add-attr :p0 :ent-type :project)
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   
                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :mario :type :ent)
                   (lat/add-attr :mario :index 0)
                   (lat/add-attr :mario :ent-type :todo-list)
                   (lat/add-attr :mario :query-term [:_])

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :luigi :type :ent)
                   (lat/add-attr :luigi :index 1)
                   (lat/add-attr :luigi :ent-type :todo-list)
                   (lat/add-attr :luigi :query-term [:_])

                   (lat/add-attr :p0 :mario :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :luigi :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :mario :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :luigi :u0 :relation-attrs #{:created-by-id :updated-by-id})))))

(deftest test-add-ents-one-level-relation-binding
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list [[:_ {:bind {:user :bloop}}]]}))
             (-> (lg/digraph [:user :bloop] [:todo-list :tl0] [:tl0 :bloop])
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :bloop :type :ent)
                 (lat/add-attr :bloop :index 0)
                 (lat/add-attr :bloop :query-term [:_ {:bind {:user :bloop}}])
                 (lat/add-attr :bloop :ent-type :user)
                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [:_ {:bind {:user :bloop}}])
                 (lat/add-attr :tl0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))))

(deftest test-add-ents-two-level-relation-binding
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo [[:_ {:bind {:user :bloop}}]]}))
             (-> (lg/digraph [:user :bloop]
                             [:todo :t0]
                             [:todo-list :tl-bound-t-0]
                             [:t0 :bloop]
                             [:t0 :tl-bound-t-0]
                             [:tl-bound-t-0 :bloop])
                 
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :bloop :type :ent)
                 (lat/add-attr :bloop :index 0)
                 (lat/add-attr :bloop :ent-type :user)
                 (lat/add-attr :bloop :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :todo :type :ent-type)
                 (lat/add-attr :t0 :type :ent)
                 (lat/add-attr :t0 :index 0)
                 (lat/add-attr :t0 :ent-type :todo)
                 (lat/add-attr :t0 :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl-bound-t-0 :type :ent)
                 (lat/add-attr :tl-bound-t-0 :index 0)
                 (lat/add-attr :tl-bound-t-0 :ent-type :todo-list)
                 (lat/add-attr :tl-bound-t-0 :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :t0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                 (lat/add-attr :t0 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                 (lat/add-attr :tl-bound-t-0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))))

(deftest test-add-ents-multiple-two-level-relation-binding
  (testing "only one bound todo list is created for the three todos"
    (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo [[3 {:bind {:user :bloop}}]]}))
               (-> (lg/digraph [:user :bloop]
                               [:todo-list :tl-bound-t-0]
                               [:todo :t0]
                               [:t0 :bloop]
                               [:t0 :tl-bound-t-0]
                               [:todo :t1]
                               [:t1 :bloop]
                               [:t1 :tl-bound-t-0]
                               [:todo :t2]
                               [:t2 :bloop]
                               [:t2 :tl-bound-t-0]
                               [:tl-bound-t-0 :bloop])
                   
                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :bloop :type :ent)
                   (lat/add-attr :bloop :index 0)
                   (lat/add-attr :bloop :ent-type :user)
                   (lat/add-attr :bloop :query-term [:_ {:bind {:user :bloop}}])

                   (lat/add-attr :todo :type :ent-type)
                   (lat/add-attr :t0 :type :ent)
                   (lat/add-attr :t0 :index 0)
                   (lat/add-attr :t0 :ent-type :todo)
                   (lat/add-attr :t0 :query-term [3 {:bind {:user :bloop}}])

                   (lat/add-attr :todo :type :ent-type)
                   (lat/add-attr :t1 :type :ent)
                   (lat/add-attr :t1 :index 1)
                   (lat/add-attr :t1 :ent-type :todo)
                   (lat/add-attr :t1 :query-term [3 {:bind {:user :bloop}}])

                   (lat/add-attr :todo :type :ent-type)
                   (lat/add-attr :t2 :type :ent)
                   (lat/add-attr :t2 :index 2)
                   (lat/add-attr :t2 :ent-type :todo)
                   (lat/add-attr :t2 :query-term [3 {:bind {:user :bloop}}])

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl-bound-t-0 :type :ent)
                   (lat/add-attr :tl-bound-t-0 :index 0)
                   (lat/add-attr :tl-bound-t-0 :ent-type :todo-list)
                   (lat/add-attr :tl-bound-t-0 :query-term [:_ {:bind {:user :bloop}}])

                   (lat/add-attr :t0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :t0 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                   (lat/add-attr :t1 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :t1 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                   (lat/add-attr :t2 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :t2 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                   (lat/add-attr :tl-bound-t-0 :bloop :relation-attrs #{:created-by-id :updated-by-id})))))

(deftest test-add-ents-bound-and-uniq
  (testing "create uniq bound todo lists per todo-list-watch uniq constraint"
    (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list-watch [[2 {:bind {:user :bloop}}]]}))
               (-> (lg/digraph [:user :bloop]
                               [:todo-list :tl-bound-tlw-0]
                               [:tl-bound-tlw-0 :bloop]
                               [:todo-list :tl-bound-tlw-1]
                               [:tl-bound-tlw-1 :bloop]
                               [:todo-list-watch :tlw0]
                               [:tlw0 :bloop]
                               [:tlw0 :tl-bound-tlw-0]
                               [:todo-list-watch :tlw1]
                               [:tlw1 :bloop]
                               [:tlw1 :tl-bound-tlw-1])
                   
                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :bloop :type :ent)
                   (lat/add-attr :bloop :index 0)
                   (lat/add-attr :bloop :ent-type :user)
                   (lat/add-attr :bloop :query-term [:_ {:bind {:user :bloop}}])

                   (lat/add-attr :todo-list-watch :type :ent-type)
                   (lat/add-attr :tlw0 :type :ent)
                   (lat/add-attr :tlw0 :index 0)
                   (lat/add-attr :tlw0 :ent-type :todo-list-watch)
                   (lat/add-attr :tlw0 :query-term [2 {:bind {:user :bloop}}])

                   (lat/add-attr :todo-list-watch :type :ent-type)
                   (lat/add-attr :tlw1 :type :ent)
                   (lat/add-attr :tlw1 :index 1)
                   (lat/add-attr :tlw1 :ent-type :todo-list-watch)
                   (lat/add-attr :tlw1 :query-term [2 {:bind {:user :bloop}}])

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl-bound-tlw-0 :type :ent)
                   (lat/add-attr :tl-bound-tlw-0 :index 0)
                   (lat/add-attr :tl-bound-tlw-0 :ent-type :todo-list)
                   (lat/add-attr :tl-bound-tlw-0 :query-term [:_ {:bind {:user :bloop}}])

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl-bound-tlw-1 :type :ent)
                   (lat/add-attr :tl-bound-tlw-1 :index 1)
                   (lat/add-attr :tl-bound-tlw-1 :ent-type :todo-list)
                   (lat/add-attr :tl-bound-tlw-1 :query-term [:_ {:bind {:user :bloop}}])

                   (lat/add-attr :tlw0 :bloop :relation-attrs #{:watcher-id})
                   (lat/add-attr :tlw0 :tl-bound-tlw-0 :relation-attrs #{:todo-list-id})

                   (lat/add-attr :tlw1 :bloop :relation-attrs #{:watcher-id})
                   (lat/add-attr :tlw1 :tl-bound-tlw-1 :relation-attrs #{:todo-list-id})

                   (lat/add-attr :tl-bound-tlw-0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :tl-bound-tlw-1 :bloop :relation-attrs #{:created-by-id :updated-by-id})))))

(deftest test-add-ents-three-level-relation-binding
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:attachment [[:_ {:bind {:user :bloop}}]]}))
             (-> (lg/digraph [:user :bloop]
                             [:attachment :a0]
                             [:todo :t-bound-a-0]
                             [:todo-list :tl-bound-a-0]
                             [:a0 :bloop]
                             [:a0 :t-bound-a-0]
                             [:t-bound-a-0 :bloop]
                             [:t-bound-a-0 :tl-bound-a-0]
                             [:tl-bound-a-0 :bloop])
                 
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :bloop :type :ent)
                 (lat/add-attr :bloop :index 0)
                 (lat/add-attr :bloop :ent-type :user)
                 (lat/add-attr :bloop :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :todo :type :ent-type)
                 (lat/add-attr :t-bound-a-0 :type :ent)
                 (lat/add-attr :t-bound-a-0 :index 0)
                 (lat/add-attr :t-bound-a-0 :ent-type :todo)
                 (lat/add-attr :t-bound-a-0 :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl-bound-a-0 :type :ent)
                 (lat/add-attr :tl-bound-a-0 :index 0)
                 (lat/add-attr :tl-bound-a-0 :ent-type :todo-list)
                 (lat/add-attr :tl-bound-a-0 :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :attachment :type :ent-type)
                 (lat/add-attr :a0 :type :ent)
                 (lat/add-attr :a0 :index 0)
                 (lat/add-attr :a0 :ent-type :attachment)
                 (lat/add-attr :a0 :query-term [:_ {:bind {:user :bloop}}])

                 (lat/add-attr :a0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                 (lat/add-attr :a0 :t-bound-a-0 :relation-attrs #{:todo-id})

                 (lat/add-attr :t-bound-a-0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                 (lat/add-attr :t-bound-a-0 :tl-bound-a-0 :relation-attrs #{:todo-list-id})

                 (lat/add-attr :tl-bound-a-0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))))
=
(deftest test-add-ents-uniq-constraint
  (is-graph= (:data (sm/add-ents {:schema td/schema} {:todo-list-watch [[2]]}))
             (-> (lg/digraph [:user :u0]
                             [:todo-list :tl0]
                             [:tl0 :u0]
                             [:todo-list :tl1]
                             [:tl1 :u0]
                             [:todo-list-watch :tlw0]
                             [:tlw0 :tl0]
                             [:tlw0 :u0]
                             [:todo-list-watch :tlw1]
                             [:tlw1 :tl1]
                             [:tlw1 :u0])
                 (lat/add-attr :user :type :ent-type)
                 (lat/add-attr :u0 :type :ent)
                 (lat/add-attr :u0 :index 0)
                 (lat/add-attr :u0 :ent-type :user)
                 (lat/add-attr :u0 :query-term [:_])
                 
                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl0 :type :ent)
                 (lat/add-attr :tl0 :index 0)
                 (lat/add-attr :tl0 :ent-type :todo-list)
                 (lat/add-attr :tl0 :query-term [:_])

                 (lat/add-attr :todo-list :type :ent-type)
                 (lat/add-attr :tl1 :type :ent)
                 (lat/add-attr :tl1 :index 1)
                 (lat/add-attr :tl1 :ent-type :todo-list)
                 (lat/add-attr :tl1 :query-term [:_])
                 
                 (lat/add-attr :todo-list-watch :type :ent-type)
                 (lat/add-attr :tlw0 :type :ent)
                 (lat/add-attr :tlw0 :index 0)
                 (lat/add-attr :tlw0 :ent-type :todo-list-watch)
                 (lat/add-attr :tlw0 :query-term [2])

                 (lat/add-attr :todo-list-watch :type :ent-type)
                 (lat/add-attr :tlw1 :type :ent)
                 (lat/add-attr :tlw1 :index 1)
                 (lat/add-attr :tlw1 :ent-type :todo-list-watch)
                 (lat/add-attr :tlw1 :query-term [2])
                 
                 (lat/add-attr :tl0 :u0 :relation-attrs #{:updated-by-id :created-by-id})
                 (lat/add-attr :tl1 :u0 :relation-attrs #{:updated-by-id :created-by-id})

                 (lat/add-attr :tlw0 :tl0 :relation-attrs #{:todo-list-id})
                 (lat/add-attr :tlw0 :u0 :relation-attrs #{:watcher-id})
                 (lat/add-attr :tlw1 :tl1 :relation-attrs #{:todo-list-id})
                 (lat/add-attr :tlw1 :u0 :relation-attrs #{:watcher-id}))))

(deftest test-bound-descendants?
  (is (sm/bound-descendants? (sm/init-db {:schema td/schema} {}) {:user :bibbity} :attachment))
  (is (not (sm/bound-descendants? (sm/init-db {:schema td/schema} {}) {:user :bibbity} :user)))
  (is (not (sm/bound-descendants? (sm/init-db {:schema td/schema} {}) {:attachment :bibbity} :user))))

(deftest queries-can-have-anon-names
  (is (= (:data (sm/add-ents {:schema td/schema} {:user [[:_] [:_]]}))
         (-> (lg/digraph [:user :u0] [:user :u1] )
             (lat/add-attr :user :type :ent-type)
             (lat/add-attr :u0 :type :ent)
             (lat/add-attr :u0 :index 0)
             (lat/add-attr :u0 :query-term [:_])
             (lat/add-attr :u0 :ent-type :user)
             (lat/add-attr :u1 :type :ent)
             (lat/add-attr :u1 :index 1)
             (lat/add-attr :u1 :query-term [:_])
             (lat/add-attr :u1 :ent-type :user)))))

(deftest handles-A->A-cycles
  (testing "Handle cycles where two entities of the same type reference each other"
    (is-graph= (:data (sm/add-ents {:schema td/cycle-schema} {:user [[:u0 {:refs {:updated-by-id :u1}}]
                                                                     [:u1 {:refs {:updated-by-id :u0}}]]}))
               (-> (lg/digraph [:user :u0] [:user :u1] [:u0 :u1] [:u1 :u0])
                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :u0 :type :ent)
                   (lat/add-attr :u0 :index 0)
                   (lat/add-attr :u0 :query-term [:u0 {:refs {:updated-by-id :u1}}])
                   (lat/add-attr :u0 :ent-type :user)
                   (lat/add-attr :u0 :u1 :relation-attrs #{:updated-by-id})

                   (lat/add-attr :u1 :type :ent)
                   (lat/add-attr :u1 :index 1)
                   (lat/add-attr :u1 :query-term [:u1 {:refs {:updated-by-id :u0}}])
                   (lat/add-attr :u1 :ent-type :user)
                   (lat/add-attr :u1 :u0 :relation-attrs #{:updated-by-id})))))

(deftest handles-A->B-cycles
  (testing "Handle cycles where two entities of the different types reference each other"
    (is-graph= (:data (sm/add-ents {:schema td/cycle-schema} {:todo      [[:t0 {:refs {:todo-list-id :tl0}}]]
                                                              :todo-list [[:tl0 {:refs {:first-todo-id :t0}}]]}))
               (-> (lg/digraph [:todo :t0] [:todo-list :tl0] [:tl0 :t0] [:t0 :tl0])
                   (lat/add-attr :todo :type :ent-type)
                   (lat/add-attr :t0 :type :ent)
                   (lat/add-attr :t0 :index 0)
                   (lat/add-attr :t0 :query-term [:t0 {:refs {:todo-list-id :tl0}}])
                   (lat/add-attr :t0 :ent-type :todo)
                   (lat/add-attr :t0 :tl0 :relation-attrs #{:todo-list-id})
                   
                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :tl0 :type :ent)
                   (lat/add-attr :tl0 :index 0)
                   (lat/add-attr :tl0 :query-term [:tl0 {:refs {:first-todo-id :t0}}])
                   (lat/add-attr :tl0 :ent-type :todo-list)
                   (lat/add-attr :tl0 :t0 :relation-attrs #{:first-todo-id})))))

;; view tests

(deftest test-attr-map
  (let [db (sm/add-ents {:schema td/schema} {:todo [[1]]})]
    (is (= {:tl0 :todo-list
            :t0  :todo
            :u0  :user}
           (sm/attr-map db :ent-type)))
    (is (= {:u0  :user}
           (sm/attr-map db :ent-type [:u0])))))

(deftest test-query-ents
  (is (= [:t0]
         (sm/query-ents (sm/add-ents {:schema td/schema} {:todo [[1]]}))))

  (is (= #{:t0 :u0}
         (set (sm/query-ents (sm/add-ents {:schema td/schema} {:user [[1]]
                                                               :todo [[1]]}))))))

(deftest test-add-ents-throws-exception-on-invalid-db
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"db is invalid"
                        (sm/add-ents {:schema []} {})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"query is invalid"
                        (sm/add-ents {:schema td/schema} {:user [[]]}))))

(deftest updates-node-attrs
  (let [db (-> (sm/add-ents {:schema td/schema} {:user [[:_]]})
               (sm/visit-ents-once :custom-attr-key (constantly "yaaaaay a key")))]
    (is (= (lat/attr (:data db) :u0 :custom-attr-key)
           "yaaaaay a key"))))

(deftest does-not-override-node-attr
  (testing "If node already has attr, subsequent invocations of visit-ents-once will not overwrite it"
    (let [db (-> (sm/add-ents {:schema td/schema} {:user [[:_]]})
                 (sm/visit-ents-once :custom-attr-key (constantly "yaaaaay a key"))
                 (sm/visit-ents-once :custom-attr-key (constantly "overwrite!")))]
      (is (= (lat/attr (:data db) :u0 :custom-attr-key)
             "yaaaaay a key")))))

(deftest test-related-ents-by-attr
  (let [db (sm/add-ents {:schema td/schema} {:todo [[1]]
                                             :project [[1 {:refs {:todo-list-ids [:tl0 :tl1]}}]]})]
    (is (= (sm/related-ents-by-attr db :t0 :todo-list-id)
           :tl0))
    (is (= (sm/related-ents-by-attr db :t0 :created-by-id)
           :u0))
    (is (= (sm/related-ents-by-attr db :p0 :todo-list-ids)
           [:tl0 :tl1]))))

(deftest polymorphic-refs
  (is-graph= (:data (sm/add-ents {:schema td/polymorphic-schema}
                                 {:watch [[1 {:refs      {:watched-id :tc0}
                                              :ref-types {:watched-id :topic-category}}]]}))
             (-> (lg/digraph [:topic-category :tc0] [:watch :w0] [:w0 :tc0])
                 (lat/add-attr :topic-category :type :ent-type)
                 (lat/add-attr :tc0 :type :ent)
                 (lat/add-attr :tc0 :index 0)
                 (lat/add-attr :tc0 :query-term [:_])
                 (lat/add-attr :tc0 :ent-type :topic-category)
                 
                 (lat/add-attr :watch :type :ent-type)
                 (lat/add-attr :w0 :type :ent)
                 (lat/add-attr :w0 :index 0)
                 (lat/add-attr :w0 :query-term [1 {:refs      {:watched-id :tc0}
                                                   :ref-types {:watched-id :topic-category}}])
                 (lat/add-attr :w0 :ent-type :watch)
                 (lat/add-attr :w0 :tc0 :relation-attrs #{:watched-id}))))

(deftest polymorphic-refs-with-ref-name-unspecified
  ;; differs from above in that we leave out {:refs {:watched-id :tc0}}
  (is-graph= (:data (sm/add-ents {:schema td/polymorphic-schema}
                                 {:watch [[1 {:ref-types {:watched-id :topic-category}}]]}))
             (-> (lg/digraph [:topic-category :tc0] [:watch :w0] [:w0 :tc0])
                 (lat/add-attr :topic-category :type :ent-type)
                 (lat/add-attr :tc0 :type :ent)
                 (lat/add-attr :tc0 :index 0)
                 (lat/add-attr :tc0 :query-term [:_])
                 (lat/add-attr :tc0 :ent-type :topic-category)
                 
                 (lat/add-attr :watch :type :ent-type)
                 (lat/add-attr :w0 :type :ent)
                 (lat/add-attr :w0 :index 0)
                 (lat/add-attr :w0 :query-term [1 {:ref-types {:watched-id :topic-category}}])
                 (lat/add-attr :w0 :ent-type :watch)
                 (lat/add-attr :w0 :tc0 :relation-attrs #{:watched-id}))))

(deftest polymorphic-refs-nested
  ;; refer to topic instead of topic-category
  ;; topic depends on topic-category and will create one
  (is-graph= (:data (sm/add-ents {:schema td/polymorphic-schema}
                                 {:watch [[1 {:refs      {:watched-id :t0}
                                              :ref-types {:watched-id :topic}}]]}))
             (-> (lg/digraph [:topic-category :tc0]
                             [:topic :t0]
                             [:watch :w0]
                             [:w0 :t0]
                             [:t0 :tc0])
                 (lat/add-attr :topic-category :type :ent-type)
                 (lat/add-attr :tc0 :type :ent)
                 (lat/add-attr :tc0 :index 0)
                 (lat/add-attr :tc0 :query-term [:_])
                 (lat/add-attr :tc0 :ent-type :topic-category)

                 (lat/add-attr :topic :type :ent-type)
                 (lat/add-attr :t0 :type :ent)
                 (lat/add-attr :t0 :index 0)
                 (lat/add-attr :t0 :query-term [:_])
                 (lat/add-attr :t0 :ent-type :topic)
                 (lat/add-attr :t0 :tc0 :relation-attrs #{:topic-category-id})
                 
                 (lat/add-attr :watch :type :ent-type)
                 (lat/add-attr :w0 :type :ent)
                 (lat/add-attr :w0 :index 0)
                 (lat/add-attr :w0 :query-term [1 {:refs      {:watched-id :t0}
                                                   :ref-types {:watched-id :topic}}])
                 (lat/add-attr :w0 :ent-type :watch)
                 (lat/add-attr :w0 :t0 :relation-attrs #{:watched-id}))))

(deftest polymorphic-refs-with-binding
  ;; refer to topic instead of topic-category
  ;; topic depends on topic-category and will create one
  (is-graph= (:data (sm/add-ents {:schema td/polymorphic-schema}
                                 {:watch [[1 {:refs      {:watched-id :t0}
                                              :bind      {:topic-category :tc100}
                                              :ref-types {:watched-id :topic}}]]}))
             (-> (lg/digraph [:topic-category :tc100]
                             [:topic :t0]
                             [:watch :w0]
                             [:w0 :t0]
                             [:t0 :tc100])
                 (lat/add-attr :topic-category :type :ent-type)
                 (lat/add-attr :tc100 :type :ent)
                 (lat/add-attr :tc100 :index 0)
                 (lat/add-attr :tc100 :query-term [:_ {:bind {:topic-category :tc100}}])
                 (lat/add-attr :tc100 :ent-type :topic-category)

                 (lat/add-attr :topic :type :ent-type)
                 (lat/add-attr :t0 :type :ent)
                 (lat/add-attr :t0 :index 0)
                 (lat/add-attr :t0 :query-term [:_ {:bind {:topic-category :tc100}}])
                 (lat/add-attr :t0 :ent-type :topic)
                 (lat/add-attr :t0 :tc100 :relation-attrs #{:topic-category-id})
                 
                 (lat/add-attr :watch :type :ent-type)
                 (lat/add-attr :w0 :type :ent)
                 (lat/add-attr :w0 :index 0)
                 (lat/add-attr :w0 :query-term [1 {:refs      {:watched-id :t0}
                                                   :bind      {:topic-category :tc100}
                                                   :ref-types {:watched-id :topic}}])
                 (lat/add-attr :w0 :ent-type :watch)
                 (lat/add-attr :w0 :t0 :relation-attrs #{:watched-id}))))

(deftest test-coll-relation-attr?
  (let [db (sm/add-ents {:schema td/schema} {:project [[1]]})]
    (is (sm/coll-relation-attr? db :p0 :todo-list-ids))
    (is (not (sm/coll-relation-attr? db :p0 :created-by-id)))))

(deftest test-ents-by-type
  (let [db (sm/add-ents {:schema td/schema} {:project [[1]]})]
    (is (= {:user #{:u0}
            :todo-list #{:tl0}
            :project #{:p0}}
           (sm/ents-by-type db)))
    (is (= {:user #{:u0}}
           (sm/ents-by-type db [:u0])))))

(deftest test-ent-relations
  (let [db (sm/add-ents {:schema td/schema}
                        {:project [[:p0 {:refs {:todo-list-ids 2}}]]
                         :todo    [[1]]})]
    (is (= {:created-by-id :u0
            :updated-by-id :u0
            :todo-list-ids #{:tl0 :tl1}}
           (sm/ent-relations db :p0)))
    (is (= {:created-by-id :u0
            :updated-by-id :u0
            :todo-list-id  :tl0}
           (sm/ent-relations db :t0)))))

(deftest test-all-ent-relations
  (let [db (sm/add-ents {:schema td/schema}
                        {:project [[:p0 {:refs {:todo-list-ids 2}}]]})]
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}
            :user      {:u0 {}}
            :todo-list {:tl0 {:created-by-id :u0
                              :updated-by-id :u0}
                        :tl1 {:created-by-id :u0
                              :updated-by-id :u0}}}
           (sm/all-ent-relations db)))
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}
            :todo-list {:tl0 {:created-by-id :u0
                              :updated-by-id :u0}}}
           (sm/all-ent-relations db [:p0 :tl0])))
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}}
           (sm/all-ent-relations db [:p0])))))

(deftest assert-schema-refs-must-exist
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"Your schema relations reference nonexistent types: "
                        (sm/add-ents {:schema {:user {:relations {:u1 [:circle :circle-id]}}}}
                                     {}))))

(deftest assert-no-dupe-prefixes
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"You have used the same prefix for multiple entity types: "
                        (sm/add-ents {:schema {:user  {:prefix :u}
                                               :user2 {:prefix :u}}}
                                     {}))))

(deftest assert-constraints-must-ref-existing-relations
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"Schema constraints reference nonexistent relation attrs: \{:user #\{:blarb\}\}"
                        (sm/add-ents {:schema {:user  {:prefix :u
                                                       :constraints {:blarb :coll}}}}
                                     {}))))

(deftest assert-query-does-not-contain-unknown-ent-types
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"The following ent types are in your query but aren't defined in your schema: #\{:bluser\}"
                        (sm/add-ents {:schema {:user  {:prefix :u}}}
                                     {:bluser [[1]]}))))

(deftest enforces-coll-schema-constraints
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"Query-relations for coll attrs must be a number or vector"
                        (sm/add-ents {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids :tl0}}]]}))))

(deftest enforces-unary-schema-constraints
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"Query-relations for unary attrs must be a keyword"
                        (sm/add-ents {:schema td/schema} {:attachment [[:_ {:refs {:todo-id [:t0 :t1]}}]]}))))
