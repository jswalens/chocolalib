(ns chocola.futures-actors
  (:require [clojure.test :refer :all]
            [chocola.core]))

(deftest escaping-task-problem
  ; sender sends 50 messages (containing their number) to receiver. We expect
  ; them to be received in consecutive order.
  (let [n-msgs      50
        received-is (atom [])
        done?       (promise)
        receiver-beh
          (behavior []
            [i]
            (do
              ;(println "Got" i)
              (swap! received-is conj i)
              (if (= i (- n-msgs 1))
                (deliver done? true))))
        receiver
          (spawn receiver-beh)
        sender-beh
          (behavior []
            [i]
            (future
              (Thread/sleep (rand-int 200))
              (send receiver i)))
        sender
          (spawn sender-beh)]
    (dotimes [i n-msgs]
      ;(println "Send" i)
      (send sender i))
    @done?
    (is (= @received-is (range n-msgs)))))
