(ns chocola.transactional-futures-resolve
  (:require [clojure.test :refer :all]
            [chocola.core]))

; TODO:
; 1. Tests with different structures, mirroring transactional_futures.clj
; 2. Tests with different resolve functions

; === DIFFERENT STRUCTURES ===

(defn summing [o p c] (+ p (- c o)))

(deftest seq-alter
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (is (= 0 @r1))
        (alter r1 inc)
        (is (= 1 @r1))
        (is (= :abc
              (deref
                (future
                  (is (= 1 @r1))
                  (alter r1 inc)
                  (is (= 2 @r1))
                  :abc))))
        (is (= 2 @r1))
        (alter r1 inc)
        (is (= 3 @r1)))
      (is (= 3 @r1)))))

(deftest concurrent-alter
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (alter r1 inc)
        (let [f (future (dotimes [j 1010] (alter r1 inc)) (is (= 1011 @r1)) :abc)]
          (dotimes [j 1020] (alter r1 inc))
          (is (= 1021 @r1))
          (is (= :abc (deref f)))
          ; (+ 1021 (- 1011 1))
          (is (= 2031 @r1))))
      ; merges and overwrites -> 1020 above ignored
      (is (= 2031 @r1)))))

(deftest concurrent-alter-1
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (alter r1 inc)
        (let [f (future (alter r1 + 20) (is (= 21 @r1)) :abc)]
          (is (= 1 @r1))
          (Thread/sleep 10)
          (is (= 1 @r1))
          (alter r1 + 10)
          (is (= 11 @r1))
          (is (= :abc (deref f)))
          (is (= 31 @r1))))
      (is (= 31 @r1)))))

(deftest concurrent-alter-2
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (alter r1 inc)
        (let [f (future (is (= 1 @r1)) (Thread/sleep 10) (is (= 1 @r1))
                  (alter r1 + 20) (is (= 21 @r1)) :abc)]
          (is (= 1 @r1))
          (Thread/sleep 10)
          (is (= 1 @r1))
          (alter r1 + 10)
          (is (= 11 @r1))
          (is (= :abc (deref f)))
          (is (= 31 @r1))))
      (is (= 31 @r1)))))

(comment ; TODO: commute doesn't work yet
(deftest concurrent-commute
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (commute r1 inc)
        (let [f (future (dotimes [j 1010] (commute r1 inc)) (is (= 1011 @r1)))]
          (dotimes [j 1020] (commute r1 inc))
          (is (= 1021 @r1))
          (deref f)
          (is (= 2031 @r1))))
      ; XXX is this logical/intuitive, also with other resolve functions?
      (is (= 2031 @r1)))))
)

(deftest concurrent-set
  (dotimes [i 100]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        ; Note: no "original" in in-tx-vals.
        (let [f (future (ref-set r1 (+ @r1 10)) (is (= 10 @r1)))]
          (ref-set r1 (+ @r1 20)) ; this deref should never read the value set in the future
          (is (= 20 @r1))
          (deref f)
          (is (= 30 @r1))))
      (is (= 30 @r1)))))

(deftest concurrent-set-no-conflict
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)
          r2 (ref 1 :resolve summing)]
      (dosync
        (let [f (future (ref-set r1 (+ @r1 10)))]
          (ref-set r2 (+ @r2 20))
          (is (= 10 (deref f))))) ; no conflicts here
      (is (= 10 @r1))
      (is (= 21 @r2)))))

(deftest concurrent-set-conflict-nondet
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)
          r2 (ref 1 :resolve summing)]
      (dosync
        (let [f (future (ref-set r1 (+ @r2 10)) (is (= 11 @r1)) (is (= 1 @r2)))]
          (ref-set r2 (+ @r1 20))
          (is (= 0 @r1))
          (is (= 20 @r2))
          (deref f)))
      ; in previous versions there was non-determinism,
      ; now deterministic:
      (is (= 11 @r1))
      (is (= 20 @r2)))))

(comment ; TODO: not sure this will work correctly yet
(deftest no-deref
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (future (Thread/sleep 10) (ref-set r1 1)))
      (is (= 1 @r1))))) ; no conflict
)

; === DIFFERENT RESOLVE FUNCTIONS ===

(deftest sum
  (dotimes [i 40]
    (let [r1 (ref 0 :resolve summing)]
      (dosync
        (alter r1 inc)
        (let [f (future (dotimes [j 100] (alter r1 inc)) (is (= 101 @r1)) :abc)]
          (dotimes [j 200] (alter r1 inc))
          (is (= 201 @r1))
          (is (= :abc @f))
          ; (+ 201 (- 101 1)) = (+ 301)
          (is (= 301 @r1))))
      (is (= 301 @r1)))))
