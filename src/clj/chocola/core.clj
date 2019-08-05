(ns chocola.core
  (:require [clojure.core.match :refer [match]]))

; Make these private functions from clojure.core available here
(def deref-future #'clojure.core/deref-future)
(def binding-conveyor-fn #'clojure.core/binding-conveyor-fn)

; ACTORS

(alter-meta! #'clojure.core/*actor* assoc :added "1.0-chocola")

(alter-var-root #'clojure.core/send
  (fn [send-original]
    (fn [receiver & args]
      (if (instance? clojure.lang.Actor receiver)
        ; message to actor
        (do
          (. clojure.lang.Actor doEnqueue receiver args)
          receiver)
        ; message to agent
        (apply send-original receiver args)))))

(alter-meta! #'clojure.core/send assoc :added "1.0-chocola")
(alter-meta! #'clojure.core/send assoc :doc
  "Dispatch an action to an agent or a message to an actor.
  Returns the agent or actor immediately.

  In case receiver is an actor, the message is put in the actor's inbox.

  In case receiver is an agent, in a thread from a thread pool,
  the state of the agent will be set to the value of:

  (apply (first args) state-of-agent (rest args))")

(defn patterns->match-clauses [patterns]
  "Convert patterns as given in behavior definition into clauses as expected by
  clojure.core.match/match."
  (->> patterns
    ; ([:ping] ping [:pong] pong)
    (partition 2)
    ; (([:ping] ping) ([:pong] pong))
    (map (fn [[pattern action]] [[(list pattern :seq)] action]))
    ; ([[([:ping] :seq)] ping] [[([:pong] :seq)] pong])
    (apply concat))) ; flatten one level
    ; ([([:ping] :seq)] ping [([:pong] :seq)] pong)
    ; Therefore, splicing this results in:
    ; [([:ping] :seq)] ping
    ; [([:pong] :seq)] pong

(alter-var-root #'clojure.core/behavior
  (fn [_original]
    (fn [&form &env behavior-pars & body]
      (let [match-clauses (patterns->match-clauses body)]
        `(binding-conveyor-fn
          (fn ~behavior-pars
            (fn [& message-pars#]
              (match [message-pars#]
                ~@match-clauses
                :else (println "error: message" message-pars#
                        "does not match any pattern")))))))))

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

(alter-var-root #'clojure.core/future-call
  (fn [_original]
    (fn [f]
      (let [f (binding-conveyor-fn f)
            fut (clojure.lang.AFuture/forkFuture ^Callable f)]
        (reify
          clojure.lang.IDeref
            (deref [_] (deref-future fut))
          clojure.lang.IBlockingDeref
            (deref [_ timeout-ms timeout-val]
              (deref-future fut timeout-ms timeout-val))
          clojure.lang.IPending
            (isRealized [_] (.isDone fut))
          java.util.concurrent.Future
            (get [_] (.get fut))
            (get [_ timeout unit] (.get fut timeout unit))
            (isCancelled [_] (.isCancelled fut))
            (isDone [_] (.isDone fut))
            (cancel [_ interrupt?] (.cancel fut interrupt?)))))))
;(alter-meta! #'clojure.core/future-call assoc :doc "TODO")
;(alter-meta! #'clojure.core/future assoc :doc "TODO")

; TRANSACTIONS

; Extend ref to deal with :resolve option.
(alter-var-root #'clojure.core/ref
  (fn [original-ref]
    (fn
      ([x]
        (original-ref x))
      ([x & options]
        (let [r (apply original-ref x options)
              opts (apply hash-map options)]
          (when (:resolve opts)
            (.setResolve r (:resolve opts)))
          r)))))
