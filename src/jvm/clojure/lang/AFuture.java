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

public class AFuture implements Callable, Future {

    // Future running in current thread (can be null)
    final static ThreadLocal<AFuture> future = new ThreadLocal<AFuture>();


    // Child futures.
    final Set<Future> children = new HashSet<Future>();
    // Transactional context
    TransactionalContext ctx = null;

    // Java Future executing this future.
    // null if executing in main thread.
    Future fut = null;

    // Function executed in this future
    final Callable fn;
    // Result of future (return value of fn)
    Object result;


    // Create a future.
    AFuture(Callable fn) {
        this.fn = fn;
    }


    // Get current future.
    // This never returns null.
    static AFuture getCurrent() {
        AFuture f = future.get();
        if (f == null) {
            // In the main thread, no future may exist.
            f = new AFuture(null);
            future.set(f);
        }
        return f;
    }

    static public boolean inTransaction() {
        return getContext() != null;
    }

    // Get this thread's transactional context (possibly null).
    static TransactionalContext getContext() {
        return getCurrent().ctx;
    }

    // Get this thread's transactional context. Throws exception if no future or
    // transaction is running in the current thread.
    static TransactionalContext getContextEx() {
        TransactionalContext ctx = getContext();
        if (ctx == null)
            throw new IllegalStateException("No transaction running");
        return ctx;
    }


    // Execute future (in this thread).
    public Object call() throws Exception {
        if (future.get() != null)
            throw new IllegalStateException("Already in a future");

        try {
            future.set(this);
            if(ctx != null && !ctx.tx.isNotKilled()) // in a killed tx
                throw new LockingTransaction.StoppedEx();
            result = fn.call();
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
    static public Future forkFuture(Callable fn) { // XXX
        AFuture current = getCurrent();

        if (inTransaction() && !current.ctx.tx.isNotKilled())
            throw new LockingTransaction.StoppedEx();

        AFuture child;
        if (!inTransaction()) {
            child = new AFuture(fn);
        } else {
            child = new AFuture(fn);
            child.ctx = new TransactionalContext(current.ctx);
        }
        current.children.add(child);
        if (inTransaction())
            current.ctx.children.add(child); // XXX
        child.fork();
        return child;
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

        // Wait for other thread to finish
        if (fut != null)
            fut.get(); // sets result
        // else: result set by call() directly XXX

        // TODO deal with case that future_b is txional but future_a not
        if (inTransaction() && this.ctx != null) { // both txional
            TransactionalContext currentCtx = AFuture.getContextEx();
            // Merge 'this' (future b) into 'current' (future a).
            currentCtx.merge(this.ctx);
        }

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

}
