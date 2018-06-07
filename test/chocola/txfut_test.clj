(ns chocola.txfut-test
  (:require [clojure.test :refer :all]
            [chocola.core :refer :all]))

(deftest tx-fut-test-1
  (testing "Simple test to start with. Will throw an exception in standard Clojure."
    (let [a (ref 0)]
      (dosync
        (let [f (future (alter a inc))]
          @f))
      (is (= 1 @a)))))
