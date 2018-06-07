(ns chocola.txfut
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest tx-fut-test-1
  (testing "Simple test to start with. Will throw an exception in standard Clojure."
    (let [a (ref 0)]
      (dosync
        (let [f (future (alter a inc))]
          @f))
      (is (= 1 @a)))))
