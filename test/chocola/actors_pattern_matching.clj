(ns chocola.actors-pattern-matching
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest simple
  (let [p (promise)
        b (behavior []
            [x] (deliver p x))
        a (spawn b)]
    (send a 5)
    (is (= (deref p 5000 false) 5))
    (is (realized? p) "Promise not delivered after 5000 ms.")))

(deftest list-pattern
  (let [p (promise)
        b (behavior []
            [[1 x]] (deliver p x))
        a (spawn b)]
    (send a [1 5])
    (is (= (deref p 5000 false) 5))
    (is (realized? p) "Promise not delivered after 5000 ms.")))

(deftest two-patterns
  (let [p1 (promise)
        p2 (promise)
        b (behavior []
            [x y] (deliver p1 y)
            [x]   (deliver p2 x))
        a (spawn b)]
    (send a 1 2)
    (send a 3)
    (is (= (deref p1 3000 false) 2))
    (is (realized? p1) "Promise 1 not delivered after 3000 ms.")
    (is (= (deref p2 3000 false) 3))
    (is (realized? p2) "Promise 2 not delivered after 3000 ms.")))

(deftest complex
  (let [results (atom [])
        done?   (promise)
        b (behavior []
            [:done]  (deliver done? true)
            [:hello] (swap! results conj [:symbol :hello])
            [1 y]    (swap! results conj [:two-param1 [1 y]])
            [x y]    (swap! results conj [:two-param  [x y]])
            [[1 y]]  (swap! results conj [:list1 [1 y]])
            [[x y]]  (swap! results conj [:list [x y]])
            [x]      (swap! results conj [:one-param x]))
        a (spawn b)]
    (send a :hello)
    (send a 1 5)
    (send a [1 5])
    (send a 2 3)
    (send a [2 4])
    (send a 8)
    (send a :hello)
    (send a :done)
    (is (deref done? 3000 false))
    (is (realized? done?) "Not done after 3000 ms.")
    (is (= @results [[:symbol :hello]
                     [:two-param1 [1 5]]
                     [:list1 [1 5]]
                     [:two-param [2 3]]
                     [:list [2 4]]
                     [:one-param 8]
                     [:symbol :hello]]))))

; TODO: test whether internal memory is still accessible + what if a pattern
; shadows an internal variable (or external even?)
