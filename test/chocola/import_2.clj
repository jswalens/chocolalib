(ns chocola.import-2)

(defn spawn-actor-that-delivers [p]
  (println (var behavior))
  (println behavior)
  (println spawn)
  (let [b (behavior [] [] (deliver p true))
        ;b (fn [] (fn [] (deliver p true)))
        a (spawn b)]
    (send a)))
