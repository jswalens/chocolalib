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

;; === Contention ===

; From clojure.org/concurrent_programming by Rich Hickey:
; In this example a vector of Refs containing integers is created (refs),
; then a set of threads are set up (pool) to run a number of iterations of
; incrementing every Ref (tasks). This creates extreme contention,
; but yields the correct result. No locks!
(defn test-contention [nitems nthreads niters]
  (let [refs  (map ref (replicate nitems 0))
        pool  (Executors/newFixedThreadPool nthreads)
        tasks (map (fn [t]
                     (fn []
                       (dotimes [n niters]
                         (dosync
                           (doseq [r refs]
                             (alter r + 1 t))))))
                (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    (map deref refs)))

(deftest contention-test
  ; 10 threads increment each of 10 refs 10000 times
  ; each ref should be incremented by 550000 in total =
  ;   (* 10000 (+ 1 2 3 4 5 6 7 8 9 10))
  ; -> (550000 550000 550000 550000 550000 550000 550000 550000 550000 550000)
  (let [res (time (test-contention 10 10 10000))]
    (is (= (count res) 10))
    (is (every? (fn [r] (= r 550000)) res))))

;; === Vector swap ===

; From http://clojure.org/refs by Rich Hickey:
; In this example a vector of references to vectors is created, each containing
; (initially sequential) unique numbers. Then a set of threads are started that
; repeatedly select two random positions in two random vectors and swap them,
; in a transaction. No special effort is made to prevent the inevitable
; conflicts other than the use of transactions.
(defn vector-swap [nvecs nitems nthreads niters]
  (let [vec-refs (vec (map (comp ref vec)
                        (partition nitems (range (* nvecs nitems)))))
        swap #(let [v1 (rand-int nvecs)
                    v2 (rand-int nvecs)
                    i1 (rand-int nitems)
                    i2 (rand-int nitems)]
               (dosync
                 (let [temp (nth (deref (vec-refs v1)) i1)]
                   (alter (vec-refs v1) assoc i1
                     (nth (deref (vec-refs v2)) i2))
                   (alter (vec-refs v2) assoc i2 temp))))
        check-distinct #(do
                         ; (prn (map deref vec-refs))
                         (is (= (* nvecs nitems)
                               (count (distinct
                                        (apply concat (map deref vec-refs)))))))]
    (check-distinct)
    (dorun (apply pcalls (repeat nthreads #(dotimes [_ niters] (swap)))))
    (check-distinct)))

(deftest vector-swap-test
  (time (vector-swap 100 10 10 100000)))

;; = DISJOINT =

; T1 and T2 update disjoint sets of references
; Goal: measure difference in speed between coarse-grained and fine-grained
; locking STM implementations
(defn disjoint-experiment [n]
  (let [count-illegal-states (atom 0)
        x (ref 0)
        y (ref 0)
        z (ref 0)
        T1 (Thread. (fn []
                      (dotimes [i n]
                        (dosync
                          (alter x inc)
                          (alter y inc)))))
        T2 (Thread. (fn []
                      (dotimes [i n]
                        (dosync
                          (alter z inc)))))]
        ;T3 (Thread. (fn []
        ;              (dotimes [i (* 2 n)]
        ;                (dosync
        ;                  (if (not (= (deref x) (deref y)))
        ;                    (swap! count-illegal-states inc))))))
    (.start T1) (.start T2) ; (.start T3)
    (.join T1) (.join T2)   ; (.join T3)
    (is (= (deref x) n))
    (is (= (deref y) n))
    (is (= (deref z) n))))

(deftest disjoint
  (time (dotimes [i 100] (disjoint-experiment 1000))))

;; = WRITE SKEW =

; write skew example, inspiration taken from R. Mark Volkmann's article:
; http://java.ociweb.com/mark/stm/article.html
;
; returns true if there is write skew

; constraint: @cats + @dogs <= 3
; 2 threads: john, mary
; @cats = 1, @dogs = 1
; john: (alter cats inc), while concurrently mary: (alter dogs inc)
; both are allowed to commit (no conflicts) => @cats + @dogs = 4
; to avoid: john must call (ensure dogs), mary must call (ensure cats)
(defn write-skew-experiment [ensure-fnA ensure-fnB]
  (let [cats (ref 1)
        dogs (ref 1)
        john (Thread. (fn []
                        (dosync
                          (ensure-fnA dogs)
                          (if (< (+ (deref cats) (deref dogs)) 3)
                            (alter cats inc)))))
        mary (Thread. (fn []
                        (dosync
                          (ensure-fnB cats)
                          (if (< (+ (deref cats) (deref dogs)) 3)
                            (alter dogs inc)))))]
    (doseq [p [john mary]] (.start p))
    (doseq [p [john mary]] (.join p))
    (> (+ (deref cats) (deref dogs)) 3)))

(deftest writeskew
  (println "no thread calls ensure: wrong")
  (println "write skews detected: "
    (count (filter true? (repeatedly 5000 #(write-skew-experiment identity identity))))
    " (expect >1)")
  (println "some threads call ensure: still wrong")
  (println "write skews detected: "
    (count (filter true? (repeatedly 5000 #(write-skew-experiment ensure identity))))
    " (expect >1)")
  (println "all threads call ensure: correct")
  (let [n (count (filter true? (repeatedly 1000 #(write-skew-experiment ensure ensure))))]
    (println "write skews detected: " n " (expect 0)")
    (is (= n 0))))

;; = COMMUTE =

; Adapted from clojure.org/concurrent_programming by Rich Hickey:
; In this example a vector of Refs containing integers is created (refs),
; then a set of threads are set up (pool) to run a number of iterations of
; incrementing every Ref (tasks). This creates extreme contention,
; but yields the correct result. No locks!

; update-fn is one of alter or commute
(defn test-commutes [nitems nthreads niters update-fn]
  (let [num-tries (atom 0)
        refs  (map ref (replicate nitems 0))
        pool  (Executors/newFixedThreadPool nthreads)
        tasks (map (fn [t]
                     (fn []
                       (dotimes [n niters]
                         (dosync
                           (swap! num-tries inc)
                           (doseq [r refs]
                             (update-fn r + 1 t))))))
                (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    {:result (map deref refs)
     :retries (- @num-tries (* nthreads niters)) }))

; 10 threads increment each of 10 refs 10000 times
; each ref should be incremented by 550000 in total =
;   (* 10000 (+ 1 2 3 4 5 6 7 8 9 10))
; -> (550000 550000 550000 550000 550000 550000 550000 550000 550000 550000)

(deftest test-alter
  ; using alter
  (let [res (time (test-commutes 10 10 10000 alter))]
    (assert (= (count (:result res)) 10))
    (assert (every? (fn [r] (= r 550000)) (:result res)))
    (println "num retries using alter: " (:retries res))))

(deftest test-commute
  ; using commute
  (let [res (time (test-commutes 10 10 10000 commute))]
    (assert (= (count (:result res)) 10))
    (assert (every? (fn [r] (= r 550000)) (:result res)))
    (println "num retries using commute: " (:retries res))))

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

(deftest test-alter
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
        niters   10000
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
