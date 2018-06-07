(ns chocola.core)

; ACTORS

; FIXME
; (def
;   ^{:added "1.0-chocola"
;     :doc "The actor currently running on this thread, else nil."
;     :tag clojure.lang.Actor} ; XXX not sure how to do this
;   'clojure.core/*actor* nil)

(alter-var-root (find-var 'clojure.core/send)
  (fn [send-original]
    "Dispatch an action to an agent or a message to an actor.
    Returns the agent or actor immediately.

    In case receiver is an actor, the message is put in the actor's inbox.

    In case receiver is an agent, in a thread from a thread pool,
    the state of the agent will be set to the value of:

    (apply (first args) state-of-agent (rest args))"
    (fn [receiver & args]
      (if (instance? clojure.lang.Actor receiver)
        (do
          (. clojure.lang.Actor doEnqueue receiver args)
          receiver)
        (apply send-via clojure.lang.Agent/pooledExecutor
          receiver (first args) (rest args))))))

(alter-meta! #'clojure.core/send assoc :added "1.0-chocola")

(defmacro behavior
  "Create behavior. A behavior consists of parameters to the behavior,
  parameters included in the message, and a body."
  {:added "1.0-chocola"}
  [behavior-pars message-pars & body]
  `(#'clojure.core/binding-conveyor-fn
    (fn ~behavior-pars (fn ~message-pars ~@body))))

(defn spawn
  "Spawn an actor with the behavior and args."
  {:added "1.0-chocola"
   :static true}
  [^clojure.lang.IFn behavior & args]
  (. clojure.lang.Actor doSpawn behavior args))

(defn become
  "In an actor, become a different behavior with args.

  behavior can be :same (or nil), in which case the same behavior is kept but
  with the new arguments."
  {:added "1.0-chocola"
   :static true}
  [^clojure.lang.IFn behavior & args]
  (let [behavior (if (= behavior :same) nil behavior)]
    (. clojure.lang.Actor doBecome behavior args)))

; FUTURES

(alter-var-root (find-var 'clojure.core/future-call)
  (fn [original-future-call]
    (fn [f]
      (println "I'M HERE")
      (original-future-call f))))
