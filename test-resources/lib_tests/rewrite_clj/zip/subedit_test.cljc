(ns rewrite-clj.zip.subedit-test
  (:require [clojure.test :refer [deftest testing is]]
            [rewrite-clj.zip :as z]
            ;; not available in bb:
            #_[rewrite-clj.zip.base :as zbase]))

(deftest t-trees
  (let [root (z/of-string "[1 #{2 [3 4] 5} 6]")]
    (testing "modifying subtrees"
      (let [loc (z/subedit-> root
                             z/next
                             z/next
                             z/next
                             (z/replace* 'x))]
        (is (= :vector (z/tag loc)))
        (is (= "[1 #{x [3 4] 5} 6]" (z/string loc)))))
    (testing "modifying the whole tree"
      (let [loc (z/edit-> (-> root z/next z/next z/next)
                          z/prev z/prev
                          (z/replace* 'x))]
        (is (= :token (z/tag loc)))
        (is (= "2" (z/string loc)))
        (is (= "[x #{2 [3 4] 5} 6]" (z/root-string loc)))))))

(deftest zipper-retains-options
  (let [zloc (z/of-string "(1 (2 (3 4 ::my-kw)))" {:auto-resolve (fn [_x] 'custom-resolved)})
        #_#_orig-opts (zbase/get-opts zloc)]
    (testing "sanity - without subzip"
      (is (= :custom-resolved/my-kw (-> zloc
                                        z/down z/right
                                        z/down z/right
                                        z/down z/rightmost z/sexpr))))
    (testing "subzip"
      (let [sub-zloc (-> zloc z/up* z/subzip z/down*)]
        ;; (is (= orig-opts (zbase/get-opts sub-zloc)))
        (is (= :custom-resolved/my-kw (-> sub-zloc
                                          z/down z/right
                                          z/down z/right
                                          z/down z/rightmost z/sexpr)))))
    (testing "edit-node"
      (let [edited-zloc (-> zloc (z/edit-node
                                  (fn [zloc-edit]
                                    (-> zloc-edit
                                        z/down z/right
                                        z/down (z/replace* 'x)))))]
        (is (= 'x (-> edited-zloc z/down z/right z/down z/sexpr)))
        ;; (is (= orig-opts (zbase/get-opts edited-zloc)))
        (is (= :custom-resolved/my-kw (-> edited-zloc
                                          z/down z/right
                                          z/down z/right
                                          z/down z/rightmost z/sexpr)))))))
