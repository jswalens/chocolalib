(ns chocola.transactions-misc
  (:require [clojure.test :refer :all]
            [chocola.core])
  (:import [java.util.concurrent Executors]))

; === io! ===

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

; === send to Agent ===

(deftest send-to-agent-out-tx-test []
  (let [ag      (agent 0)
        n-iters 70]
    (dotimes [_i n-iters]
      (send ag inc))
    (await ag)
    (is (= @ag n-iters))))

(deftest send-to-agent-in-tx-test []
  (let [r         (ref 0)
        ag        (agent 0)
        n-threads 70
        n-iters   50
        pool      (Executors/newFixedThreadPool n-threads)
        tasks (map (fn [t]
                (fn []
                  (dotimes [_i n-iters]
                    (dosync
                      (send ag inc)
                      (alter r inc)))))
                (range n-threads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    (is (= @r (* n-threads n-iters)))
    (await ag)
    (is (= @ag (* n-threads n-iters)))))
