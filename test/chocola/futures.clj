(ns chocola.futures
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest simple
  (testing "Just using futures."
    (let [f (future
              (let [f1 (future (+ 2 3))  ; 5
                    f2 (future (+ 4 5))] ; 9
                (+ @f1 (deref f2)))) ; 14
          g (future (+ 6 7))] ; 13
      (is (= 27 (+ @f @g))))))
