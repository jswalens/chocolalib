(ns chocola.transactions-futures
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest join
  (let [root (future
               (let [child1 (future 1)]
                 (dosync
                  (let [child2 (future 2)]
                    (is (= 2 @child2))))
                 ; it is not necessary to join child1 in the transaction,
                 ; although it is a child of the root future of the transaction
                 (is (= 1 @child1))
                 0))]
    (is (= 0 @root))))
