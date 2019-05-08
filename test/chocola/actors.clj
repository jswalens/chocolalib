(ns chocola.actors
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest simple
  (let [p (promise)
        b (behavior [] [] (deliver p true))
        a (spawn b)]
    (send a)
    (is (deref p 5000 false))
    (is (realized? p) "Promise not delivered after 5000 ms.")))

(deftest two-messages
  (let [p1 (promise)
        p2 (promise)
        b (behavior [] [p] (deliver p true))
        a (spawn b)]
    (send a p1)
    (send a p2)
    (is (deref p1 3000 false))
    (is (realized? p1) "Promise 1 not delivered after 3000 ms.")
    (is (deref p2 3000 false))
    (is (realized? p2) "Promise 2 not delivered after 3000 ms.")))

(deftest counter
  (let [counter
          (behavior [i]
            [msg & args]
            (case msg
              :inc (become :same (+ i 1))
              :add (become :same (+ i (first args)))
              :get (deliver (first args) i)))
        test (fn [actor n]
               (let [p (promise)]
                 (send actor :get p)
                 (is (= (deref p 5000 false) n))))
        counter1 (spawn counter 0)
        counter2 (spawn counter 0)]
    (send counter1 :inc)
    (send counter2 :inc)
    (send counter1 :add 5)
    (send counter1 :add 4)
    (send counter2 :add 9)
    (test counter1 10)
    (test counter2 10)))

(deftest spawn-test
  (let [beh (behavior [] [] true)
        act (spawn beh)]
    (is (some? act))))

(deftest send-test
  (let [beh (behavior [] [p] (deliver p true))
        act (spawn beh)
        p   (promise)]
    (send act p)
    (is (deref p 5000 false))))

(deftest become-test
  (let [beh2 (behavior []
               [msg & args]
               (deliver (first args) 2))
        beh1 (behavior []
               [msg & args]
               (case msg
                 :deliver (deliver (first args) 1)
                 :become  (become beh2)))
        act (spawn beh1)
        p1  (promise)
        p2  (promise)]
    (send act :deliver p1)
    (is (= (deref p1 5000 false) 1))
    (send act :become)
    (send act :deliver p2)
    (is (= (deref p2 5000 false) 2))))

(deftest star-actor-star-test
  (let [beh (behavior []
              [self p]
              (do (deliver p (= self *actor*))
                  (become :same)))
        act (spawn beh)
        p1  (promise)
        p2  (promise)]
    (send act act p1)
    (is (deref p1 5000 false) "*actor* should refer to the current actor")
    (send act act p2)
    (is (deref p1 5000 false) "*actor* should refer to the current actor, even after become")))

(def ^:dynamic dynamic-var 1)

(defn create-behavior-with-dynamic-var []
  (behavior []
    [p] (deliver p dynamic-var)))

(deftest binding-conveyor-test
  (let [beh1 (create-behavior-with-dynamic-var)]
    (binding [dynamic-var 2]
      (let [beh2 (create-behavior-with-dynamic-var)
            p1   (promise)
            p2   (promise)]
        (send (spawn beh1) p1)
        (send (spawn beh2) p2)
        (is (= (deref p1 1000 false) 1))
        (is (= (deref p2 1000 false) 2))))))
