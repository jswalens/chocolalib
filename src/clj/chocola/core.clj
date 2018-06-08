(ns chocola.core)

; ACTORS

(alter-meta! #'*actor* assoc :added "1.0-chocola")

(alter-var-root #'clojure.core/send
  (fn [send-original]
    (fn [receiver & args]
      (if (instance? clojure.lang.Actor receiver)
        (do
          (. clojure.lang.Actor doEnqueue receiver args)
          receiver)
        (apply send-via clojure.lang.Agent/pooledExecutor
          receiver (first args) (rest args))))))

(alter-meta! #'clojure.core/send assoc :added "1.0-chocola")
(alter-meta! #'clojure.core/send assoc :doc
  "Dispatch an action to an agent or a message to an actor.
  Returns the agent or actor immediately.

  In case receiver is an actor, the message is put in the actor's inbox.

  In case receiver is an agent, in a thread from a thread pool,
  the state of the agent will be set to the value of:

  (apply (first args) state-of-agent (rest args))")

(alter-var-root #'clojure.core/behavior
  (fn [_original]
    (fn [&form &env behavior-pars message-pars & body]
      `(#'clojure.core/binding-conveyor-fn
        (fn ~behavior-pars (fn ~message-pars ~@body))))))

(alter-meta! #'clojure.core/behavior assoc :macro true)
(alter-meta! #'clojure.core/behavior assoc :added "1.0-chocola")
(alter-meta! #'clojure.core/behavior assoc :doc
  "Create behavior. A behavior consists of parameters to the behavior,
  parameters included in the message, and a body.")

(alter-var-root #'clojure.core/spawn
  (fn [_original]
    (fn [^clojure.lang.IFn behavior & args]
      (. clojure.lang.Actor doSpawn behavior args))))

(alter-meta! #'clojure.core/spawn assoc :added "1.0-chocola")
(alter-meta! #'clojure.core/spawn assoc :static true)
(alter-meta! #'clojure.core/spawn assoc :doc
  "Spawn an actor with the behavior and args.")

(alter-var-root #'clojure.core/become
  (fn [_original]
    (fn [^clojure.lang.IFn behavior & args]
      (let [behavior (if (= behavior :same) nil behavior)]
        (. clojure.lang.Actor doBecome behavior args)))))

(alter-meta! #'clojure.core/become assoc :added "1.0-chocola")
(alter-meta! #'clojure.core/become assoc :static true)
(alter-meta! #'clojure.core/become assoc :doc
  "In an actor, become a different behavior with args.

  behavior can be :same (or nil), in which case the same behavior is kept but
  with the new arguments.")

; FUTURES

; (alter-var-root #'clojure.core/future-call
;   (fn [original-future-call]
;     (fn [f]
;       (println "I'M HERE")
;       (original-future-call f))))
