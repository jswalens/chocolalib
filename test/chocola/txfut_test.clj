(ns chocola.txfut-test
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest fut-test
  (testing "Just using futures."
    (let [f (future (println "NOW WE'RE HERE") 3)]
      (is (= 3 @f)))))

(deftest tx-fut-test-1
  (testing "Simple test to start with. Will throw an exception in standard Clojure."
    (let [a (ref 0)]
      (dosync
        (let [f (future (alter a inc))]
          @f))
      (is (= 1 @a)))))
