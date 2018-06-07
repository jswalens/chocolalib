(ns chocola.core)

(alter-var-root (find-var 'clojure.core/future-call)
  (fn [original-future-call]
    (fn [f]
      (println "I'M HERE")
      (original-future-call f))))
