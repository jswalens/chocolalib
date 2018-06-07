(ns chocola.transactional-futures
  (:require [clojure.test :refer :all]
            [chocola.core]))

; === SIMPLE ===

(deftest seq-alter
  (dotimes [i 50]
    (let [r1 (ref 0)]
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
      (is (= 3 (deref r1))))))

(deftest concurrent-alter
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (alter r1 inc)
        (let [f (future (dotimes [j 1010] (alter r1 inc)) :abc)]
          (dotimes [j 1020] (alter r1 inc))
          (is (= :abc (deref f)))))
      ; merges and overwrites -> 1020 above ignored
      (is (= 1011 (deref r1))))))

(deftest concurrent-alter-1
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (alter r1 inc)
        (let [f (future (alter r1 + 20) :abc)]
          (Thread/sleep 10)
          (alter r1 + 10)
          (is (= :abc (deref f)))))
      ; discards 10 from above
      (is (= 21 (deref r1))))))

(deftest concurrent-alter-2
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (alter r1 inc)
        (let [f (future (Thread/sleep 10) (alter r1 + 20))]
          (alter r1 + 10)
          (deref f)))
      ; discards 10 from above
      (is (= 21 (deref r1))))))

(comment
(deftest concurrent-commute-after-alter
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (alter r1 inc)
        (let [f (future (dotimes [j 1010] (commute r1 inc)))]
          (dotimes [j 1020] (commute r1 inc))
          (deref f)))
      ; XXX commute and alter are different! alter can be overwritten,
      ; commute won't. Isn't this confusing?
      (is (= 2031 (deref r1))))))
; TODO: this doesn't work because it's a commute after an alter and that's
; different from just a commute.
; (In this case, during the commit, the last in-tx-value is written; in
; the case of only commutes the commutes are re-executed.)
  )

(deftest concurrent-commute
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (commute r1 inc)
        (let [f (future (dotimes [j 1010] (commute r1 inc)))]
          (dotimes [j 1020] (commute r1 inc))
          (deref f)))
      ; XXX same as above
      (is (= 2031 (deref r1))))))

(deftest concurrent-commute-1
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (commute r1 inc)
        (let [f (future (commute r1 + 20))]
          (Thread/sleep 10)
          (commute r1 + 10)
          (deref f)))
      (is (= 31 (deref r1))))))

(deftest concurrent-commute-2
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (commute r1 inc)
        (let [f (future (Thread/sleep 10) (commute r1 + 20))]
          (commute r1 + 10)
          (deref f)))
      (is (= 31 (deref r1))))))

(deftest concurrent-set
  (dotimes [i 100]
    (let [r1 (ref 0)]
      (dosync
        (let [f (future (ref-set r1 (+ (deref r1) 10)))]
          (ref-set r1 (+ (deref r1) 20)) ; this deref should never read the value set in the future
          (deref f)))
      (is (= 10 (deref r1))))))

(deftest concurrent-set-no-conflict
  (dotimes [i 50]
    (let [r1 (ref 0)
          r2 (ref 1)]
      (dosync
        (let [f (future (ref-set r1 (+ (deref r1) 10)))]
          (ref-set r2 (+ (deref r2) 20))
          (is (= 10 (deref f))))) ; no conflicts here
      (is (= 10 (deref r1)))
      (is (= 21 (deref r2))))))

(deftest concurrent-set-conflict-nondet
  (dotimes [i 50]
    (let [r1 (ref 0)
          r2 (ref 1)]
      (dosync
        (let [f (future (ref-set r1 (+ (deref r2) 10)))]
          (ref-set r2 (+ (deref r1) 20))
          (deref f)))
      ; in previous versions there was non-determinism,
      ; now deterministic:
      (is (= 11 (deref r1)))
      (is (= 20 (deref r2))))))

(deftest no-deref
  (dotimes [i 50]
    (let [r1 (ref 0)]
      (dosync
        (future (Thread/sleep 10) (ref-set r1 1)))
      (is (= 1 (deref r1)))))) ; no conflict

; === STRUCTURED ===

(deftest none
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10))
      (is (= 11 (deref r1))))))

(deftest one-rr
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (is (= 11 (deref r1))))]
          (is (= 11 (deref r1)))
          (deref f)
          (is (= 11 (deref r1)))))
      (is (= 11 (deref r1))))))

(deftest one-rw
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (is (= 11 (deref r1))))]
          (alter r1 + 10)
          (is (= 21 (deref r1)))
          (deref f)
          (is (= 21 (deref r1)))))
      (is (= 21 (deref r1))))))

(deftest one-wr
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (alter r1 + 10)
                  (is (= 21 (deref r1))))]
          (is (= 11 (deref r1)))
          (deref f)
          (is (= 21 (deref r1)))))
      (is (= 21 (deref r1))))))

(deftest one-ww
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (alter r1 + 10)
                  (is (= 21 (deref r1))))]
          (alter r1 + 20)
          (is (= 31 (deref r1)))
          (deref f)
          (is (= 21 (deref r1)))))
      (is (= 21 (deref r1))))))

(deftest two-depth-nested
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (alter r1 + 10)
                  (is (= 21 (deref r1)))
                  (let [g (future
                            (alter r1 + 20)
                            (is (= 41 (deref r1))))]
                    (alter r1 + 30)
                    (is (= 51 (deref r1)))
                    (deref g)
                    (is (= 41 (deref r1)))))]
          (alter r1 + 40)
          (is (= 51 (deref r1)))
          (deref f)
          (is (= 41 (deref r1)))))
      (is (= 41 (deref r1))))))

(deftest two-breadth-separate
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (alter r1 + 20)
                  (is (= 31 (deref r1))))
              g (future
                  (alter r1 + 30)
                  (is (= 41 (deref r1))))]
          (alter r1 + 40)
          (is (= 51 (deref r1)))
          (deref f)
          (is (= 31 (deref r1)))
          (deref g)
          (is (= 41 (deref r1)))))
      (is (= 41 (deref r1))))))

(deftest two-breadth-co-deref
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (let [f (future
                  (is (= 11 (deref r1)))
                  (alter r1 + 20)
                  (is (= 31 (deref r1))))
              _ (alter r1 + 30) ; => r1 = 41
              g (future
                  (is (= 41 (deref r1)))
                  (alter r1 + 40)
                  (is (= 81 (deref r1)))
                  (deref f)
                  (is (= 31 (deref r1))))]
          (is (= 41 (deref r1)))
          (alter r1 + 50)
          (is (= 91 (deref r1)))
          (deref g)
          (is (= 31 (deref r1)))))
      (is (= 31 (deref r1))))))

(deftest three-breadth-two-co-deref-no-conflicts
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (is (= 11 (deref r1)))
        (let [f (future
                  (is (= 11 (deref r1)))
                  (alter r1 + 20)
                  (is (= 31 (deref r1))))
              g (future
                  (is (= 11 (deref r1)))
                  (deref f)  ; first deref of f
                  (is (= 31 (deref r1))))
              h (future
                  (is (= 11 (deref r1)))
                  (deref f)  ; second deref of f
                  (is (= 31 (deref r1))))]
          (is (= 11 (deref r1)))
          (deref g)
          (is (= 31 (deref r1)))
          (deref h)
          (is (= 31 (deref r1)))))
      (is (= 31 (deref r1))))))

(deftest three-breadth-two-co-deref-conflicts
  (dotimes [i 50]
    (let [r1 (ref 1)]
      (dosync
        (alter r1 + 10)
        (is (= 11 (deref r1)))
        (let [f (future
                  (is (= 11 (deref r1)))
                  (alter r1 + 20)
                  (is (= 31 (deref r1))))
              _ (do
                  (is (= 11 (deref r1)))
                  (alter r1 + 30)
                  (is (= 41 (deref r1))))
              g (future
                  (is (= 41 (deref r1)))
                  (alter r1 + 40)
                  (is (= 81 (deref r1)))
                  (deref f)  ; first deref of f
                  (is (= 31 (deref r1))))
              _ (do
                  (is (= 41 (deref r1)))
                  (alter r1 + 50)
                  (is (= 91 (deref r1))))
              h (future
                  (is (= 91 (deref r1)))
                  (alter r1 + 60)
                  (is (= 151 (deref r1)))
                  (deref f)  ; second deref of f
                  (is (= 31 (deref r1))))]
          (is (= 91 (deref r1)))
          (alter r1 + 70)
          (is (= 161 (deref r1)))
          (deref g)
          (is (= 31 (deref r1)))
          (deref h)
          (is (= 31 (deref r1)))))
      (is (= 31 (deref r1))))))
