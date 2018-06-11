(ns chocola.import-2)

(defn spawn-actor-that-delivers [p]
  (let [b (behavior [] [] (deliver p true))
        a (spawn b)]
    (send a)))
