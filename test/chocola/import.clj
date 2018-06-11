(ns chocola.import
  (:require [clojure.test :refer :all]
            [chocola.import-1]
            [chocola.core]))

(deftest imported
  (testing "Imported modules should be able to use our primitives without requiring chocola directly."
    (let [p (promise)]
      (chocola.import-1/spawn-actor-that-delivers p)
      (is (deref p 5000 false))
      (is (realized? p) "Promise not delivered after 5000 ms."))))
