(ns chocola.transactions-misc
  (:require [clojure.test :refer :all]
            [chocola.core])
  (:import [java.util.concurrent Executors]))

(def r (ref 0))

(deftest io []
  (dosync (ref-set r 0))
  (is (thrown? java.lang.IllegalStateException
    (dosync
      (ref-set r 1)
      (io!
        ; The code below should never execute
        (is false)))))
  (is (= @r 0))); threw so no change

(deftest io-tx-fut-1 []
  (dosync (ref-set r 0))
  (dosync
    (ref-set r 1)
    (let [f (future (ref-set r 2) (is (thrown? java.lang.IllegalStateException (io! (is false)))))]
      @f)) ; does not throw as it was caught internally
  (is (= @r 2))) ; caught so last r is valid

(deftest io-tx-fut-2 []
  (dosync (ref-set r 0))
  (is (thrown? java.util.concurrent.ExecutionException
    ; XXX The whole transaction throws this exception again, while it was
    ; already caught internally. Is this expected behavior?
    (dosync
      (ref-set r 1)
      (let [f (future (ref-set r 2) (io! (is false)))]
        (is (thrown? java.util.concurrent.ExecutionException @f))
        ; throws ExecutionException that wraps IllegalStateException in future
        (ref-set r 3)
        :ok))))
  (is (= @r 0))); threw so no change

(deftest io-tx-fut-3 []
  (dosync (ref-set r 0))
  (is (thrown? java.util.concurrent.ExecutionException
    ; The future is joined implicitly, at which point its exception bubbles up.
    ; ExecutionException wraps IllegalStateException in future.
    (dosync
      (ref-set r 1)
      (future (ref-set r 2) (io! (is false))))))
  (is (= @r 0))) ; threw so no change
