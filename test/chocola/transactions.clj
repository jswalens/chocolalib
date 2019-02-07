(ns chocola.transactions
  (:require [clojure.test :refer :all]
            [chocola.core])
  (:import [java.util.concurrent Executors]))

; Based on tests by Tom Van Cutsem
; https://github.com/tvcutsem/stm-in-clojure

;; = EXAMPLES =

;; === Bank account transfer ===

(defn transfer [amount from to]
  (dosync
    (alter from - amount)
    (alter to + amount)))

(deftest transfer-test []
  (let [accountA (ref 1500)
        accountB (ref 200)]
    (is (= 1500 (deref accountA)))
    (is (= 200 (deref accountB)))

    (transfer 100 accountA accountB)

    (is (= 1400 (deref accountA)))
    (is (= 300 (deref accountB)))))

;; = COMMUTE AFTER ALTER =

(deftest test-commute-after-alter
  (let [r (ref 0)]
    (dosync
      (is (= 0 (deref r)))
      (alter r inc)
      (is (= 1 (deref r)))
      (commute r inc)
      (is (= 2 (deref r))))
    (is (= 2 (deref r)))))

(deftest test-commute-2
  (let [r (ref 0)]
    (dosync
      (is (= 0 (deref r)))
      (commute r inc)
      (is (= 1 (deref r)))
      (commute r inc)
      (is (= 2 (deref r))))
    (is (= 2 (deref r)))))

(deftest test-alter-2
  (let [r (ref 0)]
    (dosync
      (is (= 0 (deref r)))
      (alter r inc)
      (is (= 1 (deref r)))
      (alter r inc)
      (is (= 2 (deref r))))
    (is (= 2 (deref r)))))

(deftest test-conflicting-commutes
  (let [nitems   10
        niters   5000
        nthreads 10
        refs     (map ref (replicate nitems 0))
        pool     (Executors/newFixedThreadPool nthreads)
        tasks    (map (fn [t]
                        (fn []
                          (dotimes [n niters]
                            (dosync
                              (doseq [r refs]
                                (commute r + 1 t))))))
                   (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    (doall
      (for [r refs]
        (is (= (* niters (reduce + (range (inc nthreads))))
              (deref r)))))))
