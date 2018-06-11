(ns chocola.import
  (:require [clojure.test :refer :all]
            [chocola.import-1]))

(deftest no-require
  (testing "We should be able to use chocola without a require, by injecting it in project.clj."
    (let [p (promise)
          b (behavior [] [] (deliver p true))
          a (spawn b)]
      (send a)
      (is (deref p 5000 false))
      (is (realized? p) "Promise not delivered after 5000 ms."))))

(deftest imported
  (testing "Imported modules should be able to use chocola without requiring it."
    (let [p (promise)]
      (chocola.import-1/spawn-actor-that-delivers p)
      (is (deref p 5000 false))
      (is (realized? p) "Promise not delivered after 5000 ms."))))
