(ns chocola.transactional-actors
  (:require [clojure.test :refer :all]
            [chocola.core :refer :all])) ; XXX

;(def logger (agent nil))
(defn log [& args]
  nil)
;(defn log [& args]
;  (send logger (fn [_] (apply println args))))

(defn- test-with-promise [actor msg expected timeout]
  (let [p (promise)]
    (send actor msg p)
    (is (= (deref p timeout false) expected))))

(defn- send-promises-and-wait [senders]
  (let [n (count senders)
        promises (repeatedly n promise)]
    (dotimes [i n]
      (send (nth senders i) (nth promises i)))
    (doseq [p promises]
      (is (deref p 500 false)))))

; COUNTER: uses transactions
; Does not require special changes for the combination of actors and transactions.
(deftest counter
  (testing "COUNTER - WORKS AS EXPECTED"
    (let [sum (ref 0)
          counter
          (behavior
            [i]
            [msg & args]
            (case msg
              :get
              (do
                (dosync
                  (log "my sum:" i "- total sum:" @sum))
                (deliver (first args) i))
              :inc
              (dosync
                (log "my sum:" i "+ 1 - total sum:" @sum "+ 1")
                (alter sum + 1)
                (become :same (+ i 1)))
              :add
              (let [j (first args)]
                (dosync
                  (log "my sum:" i "+" j "- total sum:" @sum "+" j)
                  (alter sum + j)
                  (become :same (+ i j))))))
          counter1 (spawn counter 0)
          counter2 (spawn counter 0)]
      (send counter1 :inc)
      (send counter2 :inc)
      (test-with-promise counter1 :get 1 1000)
      (send counter1 :add 5)
      (test-with-promise counter1 :get 6 1000)
      (send counter1 :add 4)
      (send counter2 :add 9)
      (test-with-promise counter1 :get 10 1000)
      (test-with-promise counter2 :get 10 1000)
      (is (= 20 @sum)))))

; SUMMER: send in transaction
; If send is not reverted when a transaction is reverted, its effects remain visible after a rollback.
(deftest summer-send
  (testing "SUMMER - PROBLEM WITH SEND"
    (let [n 100
          contentious-ref (ref 0)
          counter
          (behavior
            [i]
            [msg & args]
            (case msg
              :get
              (deliver (first args) i)
              :inc
              (become :same (inc i))))
          counter-actor (spawn counter 0)
          sender
          (behavior
            []
            [p]
            (dosync
              (send counter-actor :inc)
              (alter contentious-ref inc))
            ; only deliver after transaction committed
            (deliver p true))
          senders (doall (repeatedly n #(spawn sender)))
          promises (repeatedly n promise)]
      (send-promises-and-wait senders)
      ; wait until all messages have been sent to counter-actor, :get will be in the queue after those
      (test-with-promise counter-actor :get n 1000))))

; SUMMER: spawn in transaction
; If spawn is not reverted when a transaction is reverted, the new actor remainn active after a rollback.
(deftest summer-spawn
  (testing "SUMMER - PROBLEM WITH SPAWN"
    (let [n 100
          sum (ref 0)
          contentious-ref (ref 0)
          counter
          (behavior
            [i]
            [msg & args]
            (case msg
              :get
              (deliver (first args) i)
              :inc
              (dosync
                (log "my sum:" i "+ 1 - total sum:" @sum "+ 1")
                (alter sum + 1)
                (become :same (+ i 1)))))
          spawner
          (behavior
            []
            [p]
            (let [c (dosync
                      (let [c (spawn counter 0)]
                        (send c :inc)
                        (alter contentious-ref inc)
                        c))]
              (deliver p c)))
          spawners (doall (repeatedly n #(spawn spawner)))
          promises (repeatedly n promise)]
      (dotimes [i n]
        (send (nth spawners i) (nth promises i)))
      (doseq [p promises]
        (let [c (deref p 1000 false)]
          (is (not (false? c)))
          (test-with-promise c :get 1 1000)))
      (is (= n @sum)))))

; FLAGGER: become in transaction
; If become is not reverted when a transaction is reverted, the behavior is changed even after a rollback.
(deftest flagger-become
  (testing "FLAGGER - PROBLEM WITH BECOME"
    (let [total 100
          one-flag-set? (ref false)
          flagger
          (behavior
            [flag]
            [msg & args]
            (case msg
              :set-flag
              (dosync
                (when-not @one-flag-set?
                  (become :same true)
                  (ref-set one-flag-set? true)))
              ; else: flag stays false, one-flag-set? stays true
              :read-flag
              (deliver (first args) flag)))
          flaggers (doall (repeatedly total #(spawn flagger false)))]
      (doseq [f flaggers]
        (send f :set-flag))
      (let [flags (doall
                    (for [i (range total)]
                      (let [f (nth flaggers i)
                            p (promise)
                            timeout (if (= i 0) 1000 20)]
                        (send f :read-flag p)
                        (deref p timeout nil))))
            c (count flags)
            t (count (filter true? flags))
            f (count (filter false? flags))
            n (count (filter nil? flags))]
        ;(log "flags at the end:" flags)
        (log "true:" t "/" c "- false:" f "/" c "- nil:" n "/" c)
        (is (= total c))
        (is (= 1 t))
        (is (= (- total 1) f))
        (is (= 0 n))))))

(deftest tx-to-non-tx
  "Send from transaction. Receiver contains no transaction."
  (let [n 100
        n-send (ref 0)                                      ; contentious
        sender (behavior
                 [rcv]
                 [p]
                 (dosync
                   (send rcv :inc)
                   (alter n-send inc))
                 (deliver p true))
        receiver (behavior
                   [i]
                   [msg & args]
                   (case msg
                     :inc
                     (become :same (inc i))
                     :get
                     (deliver (first args) i)))
        receivers (doall (repeatedly n #(spawn receiver 0)))
        senders (doall (map #(spawn sender %) receivers))]
    (send-promises-and-wait senders)
    (is (= n @n-send))
    (doseq [r receivers]
      (test-with-promise r :get 1 100))))

(deftest tx-to-tx
  "Send from transaction. Receiver contains transaction."
  (let [n 100
        n-send (ref 0)                                      ; contentious
        n-receive (ref 0)                                   ; contentious
        sender (behavior
                 [rcv]
                 [p]
                 (dosync
                   (send rcv :inc p)
                   (alter n-send inc)))
        receiver (behavior
                   []
                   [msg p]
                   (dosync
                     (alter n-receive inc))
                   (deliver p true))
        receivers (doall (repeatedly n #(spawn receiver)))
        senders (doall (map #(spawn sender %) receivers))]
    (send-promises-and-wait senders)
    (is (= n @n-send))
    (is (= n @n-receive))))

(deftest tx-to-two-tx
  "Send from transaction. Receiver contains multiple transactions."
  (let [n 100
        n-send (ref 0)                                      ; contentious
        n-receive (ref 0)                                   ; contentious
        sender (behavior
                 [rcv]
                 [p]
                 (dosync
                   (send rcv :inc p)
                   (alter n-send inc)))
        receiver (behavior
                   []
                   [msg p]
                   (dosync
                     (alter n-receive inc))
                   (dosync
                     (alter n-receive inc))
                   (deliver p true))
        receivers (doall (repeatedly n #(spawn receiver)))
        senders (doall (map #(spawn sender %) receivers))]
    (send-promises-and-wait senders)
    (is (= n @n-send))
    (is (= (* 2 n) @n-receive))))

(deftest two-tx-to-non-tx
  "Send from and outside transaction. Receiver contains no transaction."
  (let [n 100
        n-send (ref 0)                                      ; contentious
        sender (behavior
                 [rcv]
                 [p]
                 (send rcv :inc)
                 (dosync
                   (send rcv :inc)
                   (alter n-send inc))
                 (send rcv :inc)
                 (dosync
                   (send rcv :inc)
                   (alter n-send inc))
                 (send rcv :inc)
                 (deliver p true))
        receiver (behavior
                   [i]
                   [msg & args]
                   (case msg
                     :inc
                     (become :same (inc i))
                     :get
                     (deliver (first args) i)))
        receivers (doall (repeatedly n #(spawn receiver 0)))
        senders (doall (map #(spawn sender %) receivers))]
    (send-promises-and-wait senders)
    (is (= (* 2 n) @n-send))
    (doseq [r receivers]
      (test-with-promise r :get 5 100))))

(deftest tx-to-tx-to-tx
  "Tx -> tx -> tx"
  (let [n 100
        n-first (ref 0)                                     ; contentious
        n-second (ref 0)                                    ; contentious
        n-third (ref 0)                                     ; contentious
        first_ (behavior
                 [i second]
                 [p]
                 (dosync
                   (send second :inc p)
                   (alter n-first inc)
                   (become :same (inc i) second)))
        second (behavior
                 [i third]
                 [msg p]
                 (dosync
                   (send third :inc p)
                   (alter n-second inc)
                   (become :same (inc i) third)))
        third (behavior
                [i]
                [msg & args]
                (case msg
                  :inc
                  (do
                    (dosync
                      (alter n-third inc)
                      (become :same (inc i)))
                    (deliver (first args) true))
                  :get
                  (deliver (first args) i)))
        thirds (doall (repeatedly n #(spawn third 0)))
        seconds (doall (map #(spawn second 0 %) thirds))
        firsts (doall (map #(spawn first_ 0 %) seconds))]
    (send-promises-and-wait firsts)
    (is (= n @n-first))
    (is (= n @n-second))
    (is (= n @n-third))
    (doseq [t thirds]
      (test-with-promise t :get 1 100))))

(deftest tx-to-non-tx-to-tx
  "Tx -> non-tx -> tx"
  (let [n 100
        n-first (ref 0)                                     ; contentious
        n-second (ref 0)                                    ; contentious
        n-third (ref 0)                                     ; contentious
        first_ (behavior
                 [i second]
                 [p]
                 (dosync
                   (send second :inc)
                   (alter n-first inc)
                   (become :same (inc i) second))
                 (deliver p true))
        second (behavior
                 [i third]
                 [msg & args]
                 (case msg
                   :inc
                   (do
                     (send third :inc)
                     (become :same (inc i) third))
                   :get
                   (deliver (first args) i)))
        third (behavior
                [i]
                [msg & args]
                (case msg
                  :inc
                  (dosync
                    (alter n-third inc)
                    (become :same (inc i)))
                  :get
                  (deliver (first args) i)))
        thirds (doall (repeatedly n #(spawn third 0)))
        seconds (doall (map #(spawn second 0 %) thirds))
        firsts (doall (map #(spawn first_ 0 %) seconds))]
    (send-promises-and-wait firsts)
    (is (= n @n-first))
    (doseq [s seconds]
      (test-with-promise s :get 1 1000))
    (is (= n @n-third))
    (doseq [t thirds]
      (test-with-promise t :get 1 100))))
