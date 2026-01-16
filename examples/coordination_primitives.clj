(ns examples.coordination-primitives
  "Demonstrates testing Missionary's coordination primitives with missionary-testkit.

  Coordination primitives enable communication and synchronization between
  concurrent tasks:

  - m/mbx  (mailbox)       - async queue: post(1-arity) never blocks, fetch(2-arity task) blocks
  - m/dfv  (deferred value) - single-assignment: assign(1-arity) first wins, deref(2-arity task) blocks
  - m/rdv  (rendezvous)    - sync handoff: give(1-arity returns task), take(2-arity task)
  - m/sem  (semaphore)     - resource limiting: release(0-arity), acquire(2-arity task)

  API pattern: All primitives return a single function that behaves differently
  based on arity:
  - mbx: (mbx value) posts, (mbx success failure) fetches
  - dfv: (dfv value) assigns, (dfv success failure) derefs
  - rdv: (rdv value) returns give-task, (rdv success failure) takes
  - sem: (sem) releases, (sem success failure) acquires

  These primitives are fully deterministic under missionary-testkit's scheduler."
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; m/mbx - Mailbox (Async Queue)
;; =============================================================================
;;
;; Mailbox is an unbounded async queue. (m/mbx) returns a function:
;; - (mbx value) - post: immediately pushes value, returns nil
;; - (mbx s f)   - fetch: task that blocks until value available

(defn mailbox-basic-example
  "Basic mailbox: producer puts values, consumer takes them."
  []
  (let [mbx (m/mbx)
        results (atom [])]
    (m/sp
      ;; Producer: post some values (1-arity = post)
      (mbx :a)
      (mbx :b)
      (mbx :c)

      ;; Consumer: fetch values (2-arity = task, use with m/?)
      ;; Order preserved - FIFO
      (swap! results conj (m/? mbx))
      (swap! results conj (m/? mbx))
      (swap! results conj (m/? mbx))

      @results)))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (mailbox-basic-example))))
  ;; => [:a :b :c]
  )

(defn mailbox-producer-consumer
  "Classic producer-consumer pattern with mailbox."
  []
  (let [mbx (m/mbx)
        consumed (atom [])]
    (m/sp
      (m/? (m/join vector
             ;; Producer: generates items with delays
             (m/sp
               (doseq [item [:task-1 :task-2 :task-3]]
                 (m/? (m/sleep 10))
                 (mbx item))  ; post
               :producer-done)

             ;; Consumer: processes items
             (m/sp
               (dotimes [_ 3]
                 (let [item (m/? mbx)]  ; fetch
                   (swap! consumed conj item)
                   (m/? (m/sleep 5))))  ; simulate processing time
               @consumed))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (mailbox-producer-consumer))))
  ;; => [:producer-done [:task-1 :task-2 :task-3]]
  )

(defn mailbox-multiple-producers
  "Multiple producers, single consumer - mailbox handles interleaving."
  []
  (let [mbx (m/mbx)
        results (atom [])]
    (m/sp
      (m/? (m/join vector
             ;; Producer A
             (m/sp
               (mbx [:a 1])
               (m/? (mt/yield))
               (mbx [:a 2])
               :producer-a-done)

             ;; Producer B
             (m/sp
               (mbx [:b 1])
               (m/? (mt/yield))
               (mbx [:b 2])
               :producer-b-done)

             ;; Consumer
             (m/sp
               (dotimes [_ 4]
                 (swap! results conj (m/? mbx)))
               @results))))))

(comment
  ;; Different interleavings produce different orderings
  (mt/with-determinism
    (mt/explore-interleavings
      mailbox-multiple-producers
      {:num-samples 20 :seed 42}))
  )

;; =============================================================================
;; m/dfv - Deferred Value (Promise/Future)
;; =============================================================================
;;
;; Deferred value is a single-assignment cell. (m/dfv) returns a function:
;; - (dfv value) - assign: binds value if not bound, returns bound value
;; - (dfv s f)   - deref: task completing with bound value
;;
;; Multiple derefs return the same value. Useful for one-shot results.

(defn dfv-basic-example
  "Basic deferred value: set once, read many times."
  []
  (let [dfv (m/dfv)]
    (m/sp
      ;; Assign the value (1-arity)
      (dfv :the-answer)

      ;; Multiple derefs all return the same value (2-arity = task)
      (let [r1 (m/? dfv)
            r2 (m/? dfv)
            r3 (m/? dfv)]
        [r1 r2 r3]))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (dfv-basic-example))))
  ;; => [:the-answer :the-answer :the-answer]
  )

(defn dfv-first-write-wins
  "First write to deferred wins, subsequent writes return first value."
  []
  (let [dfv (m/dfv)]
    (m/sp
      (let [r1 (dfv :first)   ; returns :first (bound)
            r2 (dfv :second)  ; returns :first (already bound)
            r3 (dfv :third)]  ; returns :first (already bound)
        {:assign-results [r1 r2 r3]
         :deref-result (m/? dfv)}))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (dfv-first-write-wins))))
  ;; => {:assign-results [:first :first :first] :deref-result :first}
  )

(defn dfv-async-result
  "Deferred as async result: worker computes, waiter receives."
  []
  (let [result (m/dfv)]
    (m/sp
      (m/? (m/join vector
             ;; Worker: compute result asynchronously
             (m/sp
               (m/? (m/sleep 100))  ; simulate computation
               (result (* 6 7))    ; assign
               :worker-done)

             ;; Waiter: block until result available
             (m/sp
               (let [answer (m/? result)]  ; deref
                 {:got answer :at (mt/clock)})))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (dfv-async-result))))
  ;; => [:worker-done {:got 42 :at 100}]
  )

(defn dfv-racing-writers
  "Race condition: multiple writers, first one wins."
  []
  (let [dfv (m/dfv)
        who-wrote (atom nil)]
    (m/sp
      (m/? (m/join vector
             ;; Writer A
             (m/sp
               (m/? (mt/yield))
               (when (= :from-a (dfv :from-a))
                 (reset! who-wrote :a))
               :a-done)

             ;; Writer B
             (m/sp
               (m/? (mt/yield))
               (when (= :from-b (dfv :from-b))
                 (reset! who-wrote :b))
               :b-done)

             ;; Reader
             (m/sp
               (m/? (mt/yield))
               (m/? (mt/yield))
               {:value (m/? dfv)
                :first-writer @who-wrote}))))))

(comment
  ;; Explore which writer wins under different interleavings
  (mt/with-determinism
    (mt/explore-interleavings
      dfv-racing-writers
      {:num-samples 30 :seed 42}))
  )

;; =============================================================================
;; m/rdv - Rendezvous (Synchronous Handoff)
;; =============================================================================
;;
;; Rendezvous is a synchronization point. (m/rdv) returns a function:
;; - (rdv value) - give: returns a TASK that blocks until matched with take
;; - (rdv s f)   - take: task completing with transferred value
;;
;; Both sides must arrive for the handoff to complete. Zero-buffered channel.

(defn rdv-basic-example
  "Basic rendezvous: sender and receiver must meet."
  []
  (let [rdv (m/rdv)]
    (m/sp
      (m/? (m/join vector
             ;; Sender: (rdv value) returns a task, await it
             (m/sp
               (m/? (rdv :handoff-value))  ; give - blocks until receiver ready
               :sender-done)

             ;; Receiver: (rdv s f) is the take task
             (m/sp
               (let [v (m/? rdv)]  ; take - blocks until giver ready
                 {:received v})))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (rdv-basic-example))))
  ;; => [:sender-done {:received :handoff-value}]
  )

(defn rdv-ping-pong
  "Ping-pong: two tasks alternate using two rendezvous channels."
  []
  (let [ping-rdv (m/rdv)
        pong-rdv (m/rdv)
        log (atom [])]
    (m/sp
      (m/? (m/join vector
             ;; Player A: sends ping, receives pong
             (m/sp
               (dotimes [i 3]
                 (swap! log conj [:a-sends-ping i])
                 (m/? (ping-rdv i))         ; give ping
                 (let [response (m/? pong-rdv)]  ; take pong
                   (swap! log conj [:a-got-pong response])))
               :a-done)

             ;; Player B: receives ping, sends pong
             (m/sp
               (dotimes [_ 3]
                 (let [ping-val (m/? ping-rdv)]  ; take ping
                   (swap! log conj [:b-got-ping ping-val])
                   (swap! log conj [:b-sends-pong (inc ping-val)])
                   (m/? (pong-rdv (inc ping-val)))))  ; give pong
               :b-done)))
      @log)))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (rdv-ping-pong))))
  ;; Alternating: a-sends -> b-receives -> b-sends -> a-receives -> repeat
  )

(defn rdv-synchronization-barrier
  "Using rendezvous as a synchronization barrier."
  []
  (let [ready-rdv (m/rdv)
        go-rdv (m/rdv)
        worker-started (atom false)]
    (m/sp
      (m/? (m/join vector
             ;; Coordinator: waits for worker ready, then signals go
             (m/sp
               (m/? ready-rdv)       ; take - wait for worker
               (m/? (go-rdv :start!))  ; give - signal go
               :coordinator-done)

             ;; Worker: signals ready, waits for go
             (m/sp
               (reset! worker-started true)
               (m/? (ready-rdv :worker-ready))  ; give - signal ready
               (let [signal (m/? go-rdv)]       ; take - wait for go
                 {:started-at (mt/clock) :signal signal})))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (rdv-synchronization-barrier))))
  ;; Both tasks synchronize at the barrier
  )

;; =============================================================================
;; m/sem - Semaphore (Resource Limiting)
;; =============================================================================
;;
;; Semaphore controls access to limited resources. (m/sem n) returns a function:
;; - (sem)    - release: immediately makes a token available, returns nil
;; - (sem s f) - acquire: task completing with nil when token available
;;
;; Initial permits are specified in (m/sem n).

(defn sem-basic-example
  "Basic semaphore: limit concurrent access."
  []
  (let [sem (m/sem 2)  ; 2 initial permits
        log (atom [])]
    (m/sp
      ;; Acquire both permits (2-arity = task)
      (m/? sem)
      (swap! log conj :acquired-1)
      (m/? sem)
      (swap! log conj :acquired-2)

      ;; Release one (0-arity = release)
      (sem)
      (swap! log conj :released-1)

      ;; Can acquire again
      (m/? sem)
      (swap! log conj :acquired-3)

      @log)))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (sem-basic-example))))
  ;; => [:acquired-1 :acquired-2 :released-1 :acquired-3]
  )

(defn sem-connection-pool
  "Semaphore as connection pool: limit concurrent DB connections."
  []
  (let [sem (m/sem 2)  ; max 2 concurrent connections
        active-connections (atom 0)
        max-observed (atom 0)]
    (m/sp
      ;; Launch multiple "queries" that need connections
      (m/? (m/join vector
             ;; Query A
             (m/sp
               (m/? sem)  ; acquire
               (swap! active-connections inc)
               (swap! max-observed max @active-connections)
               (m/? (m/sleep 50))  ; simulate query
               (swap! active-connections dec)
               (sem)  ; release
               :query-a-done)

             ;; Query B
             (m/sp
               (m/? sem)
               (swap! active-connections inc)
               (swap! max-observed max @active-connections)
               (m/? (m/sleep 50))
               (swap! active-connections dec)
               (sem)
               :query-b-done)

             ;; Query C (must wait for A or B to finish)
             (m/sp
               (m/? sem)
               (swap! active-connections inc)
               (swap! max-observed max @active-connections)
               (m/? (m/sleep 50))
               (swap! active-connections dec)
               (sem)
               :query-c-done)

             ;; Query D (must wait)
             (m/sp
               (m/? sem)
               (swap! active-connections inc)
               (swap! max-observed max @active-connections)
               (m/? (m/sleep 50))
               (swap! active-connections dec)
               (sem)
               :query-d-done)))
      {:max-concurrent @max-observed})))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (sem-connection-pool))))
  ;; => {:max-concurrent 2}
  ;; Semaphore ensures never more than 2 concurrent
  )

(defn sem-mutex
  "Semaphore(1) as mutex: protect critical section."
  []
  (let [mutex (m/sem 1)  ; mutex = semaphore with 1 permit
        shared-counter (atom 0)
        log (atom [])]
    (m/sp
      (m/? (m/join vector
             ;; Worker A
             (m/sp
               (dotimes [_ 3]
                 (m/? mutex)  ; acquire lock
                 (swap! log conj [:a :enter @shared-counter])
                 (swap! shared-counter inc)
                 (m/? (mt/yield))  ; yield while holding lock
                 (swap! log conj [:a :exit @shared-counter])
                 (mutex))  ; release lock
               :a-done)

             ;; Worker B
             (m/sp
               (dotimes [_ 3]
                 (m/? mutex)
                 (swap! log conj [:b :enter @shared-counter])
                 (swap! shared-counter inc)
                 (m/? (mt/yield))
                 (swap! log conj [:b :exit @shared-counter])
                 (mutex))
               :b-done)))
      {:final-count @shared-counter
       :log @log})))

(comment
  ;; Mutex ensures exclusive access even with yields
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (sem-mutex))))
  )

;; =============================================================================
;; Combined Example: Task Queue with Workers
;; =============================================================================
;;
;; Realistic example combining multiple primitives:
;; - Mailbox for task queue
;; - Semaphore to limit concurrent workers
;; - Deferred for completion signal

(defn task-queue-example
  "Task queue with limited workers using mbx + sem + dfv."
  []
  (let [;; Task queue (mailbox)
        task-queue (m/mbx)

        ;; Worker pool limit (semaphore)
        worker-sem (m/sem 2)

        ;; Completion signal (deferred)
        done-dfv (m/dfv)

        processed (atom [])
        tasks-remaining (atom 5)]
    (m/sp
      (m/? (m/join vector
             ;; Producer: enqueue tasks
             (m/sp
               (doseq [task [:task-a :task-b :task-c :task-d :task-e]]
                 (task-queue task)  ; post
                 (m/? (m/sleep 10)))
               :producer-done)

             ;; Worker 1
             (m/sp
               (loop []
                 (m/? worker-sem)  ; acquire
                 (let [task (m/? task-queue)]  ; fetch
                   (swap! processed conj [:w1 task (mt/clock)])
                   (m/? (m/sleep 25))  ; process
                   (worker-sem)  ; release
                   (when (zero? (swap! tasks-remaining dec))
                     (done-dfv :all-done)))  ; signal completion
                 (when (pos? @tasks-remaining)
                   (recur)))
               :worker-1-done)

             ;; Worker 2
             (m/sp
               (loop []
                 (m/? worker-sem)
                 (let [task (m/? task-queue)]
                   (swap! processed conj [:w2 task (mt/clock)])
                   (m/? (m/sleep 25))
                   (worker-sem)
                   (when (zero? (swap! tasks-remaining dec))
                     (done-dfv :all-done)))
                 (when (pos? @tasks-remaining)
                   (recur)))
               :worker-2-done)

             ;; Monitor: wait for completion
             (m/sp
               (m/? done-dfv)  ; deref - wait for completion
               {:processed @processed
                :completion-time (mt/clock)}))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (task-queue-example))))
  )

;; =============================================================================
;; Combined Example: Request-Response with Timeout
;; =============================================================================
;;
;; Using rendezvous for request-response pattern with deferred for response.

(defn request-response-example
  "Request-response pattern: client sends request, server responds via deferred."
  []
  (let [;; Request channel (rendezvous for backpressure)
        request-rdv (m/rdv)]
    (m/sp
      (m/? (m/join vector
             ;; Server: handle requests
             (m/sp
               (dotimes [_ 3]
                 (let [{:keys [id payload response-dfv]} (m/? request-rdv)]  ; take request
                   ;; Process request
                   (m/? (m/sleep 20))
                   ;; Send response via the deferred (assign)
                   (response-dfv {:id id :result (* payload 2)})))
               :server-done)

             ;; Client: send requests and await responses
             (m/sp
               (let [responses (atom [])]
                 (doseq [i [1 2 3]]
                   (let [response-dfv (m/dfv)]
                     ;; Send request with response channel (give)
                     (m/? (request-rdv {:id i
                                        :payload (* i 10)
                                        :response-dfv response-dfv}))
                     ;; Await response (deref)
                     (let [resp (m/? response-dfv)]
                       (swap! responses conj resp))))
                 @responses)))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (request-response-example))))
  ;; => [:server-done [{:id 1 :result 20} {:id 2 :result 40} {:id 3 :result 60}]]
  )

;; =============================================================================
;; Testing Interleavings
;; =============================================================================

(defn make-interleaving-test
  "Test that coordination primitives work correctly under all interleavings."
  []
  (let [mbx (m/mbx)
        results (atom [])]
    (m/sp
      (m/? (m/join vector
             (m/sp
               (mbx 1)  ; post
               (m/? (mt/yield))
               (mbx 2)
               :producer-done)
             (m/sp
               (swap! results conj (m/? mbx))  ; fetch
               (m/? (mt/yield))
               (swap! results conj (m/? mbx))
               @results)))
      ;; Property: both values received, order preserved
      @results)))

(comment
  (mt/with-determinism
    (mt/check-interleaving
      make-interleaving-test
      {:num-tests 100
       :seed 42
       :property (fn [result]
                   ;; Results should always be [1 2] regardless of interleaving
                   (= [1 2] result))}))
  ;; => {:ok? true ...}
  )

;; =============================================================================
;; RUNNING THE EXAMPLES
;; =============================================================================

(defn run-examples []
  (println "=== Mailbox Examples ===")

  (println "\n1. Basic mailbox (FIFO queue):")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (mailbox-basic-example))))]
    (println "   " result))

  (println "\n2. Producer-consumer:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (mailbox-producer-consumer))))]
    (println "   " result))

  (println "\n=== Deferred Value Examples ===")

  (println "\n3. Basic deferred (read many times):")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (dfv-basic-example))))]
    (println "   " result))

  (println "\n4. First write wins:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (dfv-first-write-wins))))]
    (println "   " result))

  (println "\n5. Async result:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (dfv-async-result))))]
    (println "   " result))

  (println "\n=== Rendezvous Examples ===")

  (println "\n6. Basic rendezvous (sync handoff):")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (rdv-basic-example))))]
    (println "   " result))

  (println "\n7. Ping-pong:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (rdv-ping-pong))))]
    (println "   Log:" result))

  (println "\n=== Semaphore Examples ===")

  (println "\n8. Basic semaphore:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (sem-basic-example))))]
    (println "   " result))

  (println "\n9. Connection pool (max 2 concurrent):")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (sem-connection-pool))))]
    (println "   " result))

  (println "\n=== Combined Examples ===")

  (println "\n10. Request-response pattern:")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (request-response-example))))]
    (println "   " result))

  (println "\n=== Interleaving Test ===")
  (println "\n11. Verify mailbox order under all interleavings:")
  (let [result (mt/with-determinism
                 (mt/check-interleaving
                   make-interleaving-test
                   {:num-tests 50
                    :seed 42
                    :property (fn [r] (= [1 2] r))}))]
    (println "    ok?" (:ok? result) "iterations:" (:iterations-run result))))

(comment
  (run-examples))
