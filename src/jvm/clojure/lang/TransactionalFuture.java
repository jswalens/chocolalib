/**
 *   Copyright (c). All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import java.util.*;
import java.util.concurrent.*;

public class TransactionalFuture implements Callable, Future {

    // Future running in current thread (can be null)
    final static ThreadLocal<TransactionalFuture> future = new ThreadLocal<TransactionalFuture>();


    // Child futures.
    final Set<Future> children = new HashSet<Future>();
    // Transactional context
    TransactionalContext ctx;

    // Java Future executing this future.
    // null if executing in main thread.
    Future fut = null;

    // Function executed in this future
    final Callable fn;
    // Result of future (return value of fn)
    Object result;


    TransactionalFuture(LockingTransaction tx, TransactionalFuture parent,
                        Callable fn) {
        this.fn = fn;
        if (parent != null)
            this.ctx = new TransactionalContext(tx, parent.ctx);
        else
            this.ctx = new TransactionalContext(tx, null);
    }


    // Is this thread in a transactional future?
    static public boolean isCurrent() {
        return getCurrent() != null;
    }

    // Get this thread's future (possibly null).
    static TransactionalFuture getCurrent() {
        return future.get();
    }

    // Get this thread's future. Throws exception if no future/transaction is
    // running in the current thread.
    static TransactionalFuture getEx() {
        TransactionalFuture f = future.get();
        if (f == null) {
            throw new IllegalStateException("No transaction running");
        }
        return f;
    }


    // Execute future (in this thread).
    public Object call() throws Exception {
        if(!ctx.tx.isNotKilled())
            throw new LockingTransaction.StoppedEx();

        TransactionalFuture f = future.get();
        if (f != null)
            throw new IllegalStateException("Already in a future");

        try {
            future.set(this);
            result = fn.call();
        } finally {
            future.remove();
        }
        return result;
    }

    // Execute future (in this thread), and wait for all sub-futures to finish.
    // This will throw an ExecutionException if an inner future threw an
    // exception (including StoppedEx or RetryEx).
    public Object callAndWait() throws Exception {
        if(!ctx.tx.isNotKilled())
            throw new LockingTransaction.StoppedEx();

        TransactionalFuture f = future.get();
        if (f != null)
            throw new IllegalStateException("Already in a future");

        try {
            future.set(this);
            result = fn.call();

            // Wait for all futures to finish
            // This is safe to do in this thread, as the current future's body
            // has finished.
            for (Future child : children) {
                child.get();
            }
        } finally {
            future.remove();
        }
        return result;
    }

    // Execute future in another thread.
    public void fork() {
        fut = Agent.soloExecutor.submit(this);
    }

    // Fork future: outside transaction regular future, in transactional a
    // transactional future.
    static public Future forkFuture(Callable fn) {
        TransactionalFuture current = TransactionalFuture.getCurrent();
        if (current == null) { // outside transaction
            return Agent.soloExecutor.submit(fn);
        } else if (current.ctx == null) {
            Future child = Agent.soloExecutor.submit(fn);
            current.children.add(child);
            return child;
        } else { // inside transaction
            if (!current.ctx.tx.isNotKilled())
                throw new LockingTransaction.StoppedEx();
            TransactionalFuture child = new TransactionalFuture(current.ctx.tx,
                    current, fn);
            child.fork();
            current.children.add(child);
            return child;
        }
    }


    // Attempts to cancel execution of this task.
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (fut != null)
            return fut.cancel(mayInterruptIfRunning);
        else
            return false;
    }

    // Waits if necessary for the computation to complete, and then retrieves
    // its result.
    // Should only be called in another future.
    // Throws ExecutionException if an exception occurred in the future. (This
    // might be a RetryEx or a StoppedEx!)
    public Object get() throws ExecutionException, InterruptedException {
        // Note: in future_a, we call future_b.get()
        // => this = future_b; current = future_a
        TransactionalFuture current = TransactionalFuture.getEx();

        // Wait for other thread to finish
        if (fut != null)
            fut.get(); // sets result
        // else: result set by call() directly

        // Merge into current
        current.ctx.merge(this.ctx);

        return result;
    }

    // Waits if necessary for at most the given time for the computation to
    // complete, and then retrieves its result, if available.
    public Object get(long timeout, TimeUnit unit) throws InterruptedException,
    ExecutionException, TimeoutException {
        if (fut != null)
            return fut.get(timeout, unit);
        else
            return result;
    }

    // Returns true if this task was cancelled before it completed normally.
    public boolean isCancelled() {
        if (fut != null)
            return fut.isCancelled();
        else
            return false;
    }

    // Returns true if this task completed.
    public boolean isDone() {
        if (fut != null)
            return fut.isCancelled();
        else
            return result != null; // XXX could also mean the result was actually null?
    }


    // Indicate future as having stopped (with certain transaction state).
    // OK to call twice (idempotent).
    // FIXME: remove this?
    void stop(int status) {
        ctx.stop(status);
    }

}
