/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 25, 2007 */

package clojure.lang;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Ref extends ARef implements IFn, Comparable<Ref>, IRef {
    public static final int LOCK_WAIT_MSECS = 100;

    // Each ref has a unique id, used to order them
    static final AtomicLong ids = new AtomicLong();

    public int compareTo(Ref ref) {
        if (this.id == ref.id)
            return 0;
        else if (this.id < ref.id)
            return -1;
        else
            return 1;
    }

    public int hashCode() {
        return (int) this.id;
        // in case the integer overflows, this re-uses the old values, which is
        // correct behavior
    }

    // Ref value and time point at which they were committed
    public static class TVal {
        Object val;
        long point;
        // tvals form a circular list
        TVal prior;
        TVal next;

        TVal(Object val, long point, TVal prior) {
            this.val = val;
            this.point = point;
            this.prior = prior;
            this.next = prior.next;
            this.prior.next = this;
            this.next.prior = this;
        }

        TVal(Object val, long point) {
            this.val = val;
            this.point = point;
            this.next = this;
            this.prior = this;
        }

    }


    // Circular list of values and time point at which they were committed
    TVal tvals;
    // Number of faults: gets were no recent-enough version could be found
    final AtomicInteger faults;
    // Lock
    final ReentrantReadWriteLock lock;
    // Latest transaction that has written to this ref
    LockingTransaction.Info latestWriter;
    // Unique id
    final long id;

    volatile IFn resolve = null;
    volatile int minHistory = 0;
    volatile int maxHistory = 10;


    public Ref(Object initVal) {
        this(initVal, null);
    }

    public Ref(Object initVal, IPersistentMap meta) {
        super(meta);
        tvals = new TVal(initVal, 0);
        faults = new AtomicInteger();
        lock = new ReentrantReadWriteLock();
        latestWriter = null;
        id = ids.getAndIncrement();
    }


    // Custom resolve function, set in clojure.core.

    public IFn getResolve() {
        return resolve;
    }

    public Ref setResolve(IFn resolve) {
        this.resolve = resolve;
        return this;
    }

    // Stuff for history, exposed through clojure.core.

    public int getMinHistory() {
        return minHistory;
    }

    public Ref setMinHistory(int minHistory) {
        this.minHistory = minHistory;
        return this;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public Ref setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
        return this;
    }

    public void trimHistory() {
        try {
            lock.writeLock().lock();
            if (tvals != null) {
                tvals.next = tvals;
                tvals.prior = tvals;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getHistoryCount() {
        try {
            lock.writeLock().lock();
            return histCount();
        } finally {
            lock.writeLock().unlock();
        }
    }

    int histCount() {
        if (tvals == null)
            return 0;
        else {
            int count = 0;
            for (TVal tv = tvals.next; tv != tvals; tv = tv.next)
                count++;
            return count;
        }
    }


    // Acquire/release read/write lock

    void lockRead() {
        lock.readLock().lock();
    }

    void unlockRead() {
        lock.readLock().unlock();
    }

    void unlockWrite() {
        lock.writeLock().unlock();
    }

    void tryWriteLock() {
        try {
            if (!lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS))
                throw new LockingTransaction.RetryEx();
        } catch (InterruptedException e) {
            throw new LockingTransaction.RetryEx();
        }
    }

    // Lock the ref for writing, for the given transaction.
    // Returns the most recent val.
    Object lockWrite(LockingTransaction tx) {
        boolean locked = false;
        try {
            tryWriteLock();
            locked = true;

            if (tvals != null && tvals.point > tx.readPoint)
                throw new LockingTransaction.RetryEx();

            LockingTransaction.Info latest = latestWriter;

            // Write lock conflict: someone already locked this
            if (latest != null && latest != tx.info && latest.running()) {
                boolean barged = tx.barge(latest);
                // Try to barge other, if it didn't work, unlock and "block and
                // bail" (i.e. stop this transaction, wait until other one has
                // finished, then retry).
                if (!barged) {
                    unlockWrite();
                    locked = false;
                    return tx.blockAndBail(latest);
                }
            }
            latestWriter = tx.info;
            // Note: even if we do tx.info = null at a later point, this keeps
            // a pointer to the original info
            return tvals == null ? null : tvals.val;
        } finally {
            if (locked)
                unlockWrite();
        }
    }


    // The latest value.
    // OK to call outside transaction.
    Object currentVal() {
        try {
            lock.readLock().lock();
            if (tvals != null)
                return tvals.val;
            throw new IllegalStateException(this.toString() + " is unbound.");
        } finally {
            lock.readLock().unlock();
        }
    }

    public Object deref() {
        TransactionalFuture f = TransactionalFuture.getCurrent();
        if (f == null)
            return currentVal();
        return f.doGet(this);
    }

    public Object set(Object val) {
        return TransactionalFuture.getEx().doSet(this, val);
    }

    public Object commute(IFn fn, ISeq args) {
        return TransactionalFuture.getEx().doCommute(this, fn, args);
    }

    public Object alter(IFn fn, ISeq args) {
        TransactionalFuture f = TransactionalFuture.getEx();
        return f.doSet(this, fn.applyTo(RT.cons(f.doGet(this), args)));
    }

    public void touch() {
        TransactionalFuture.getEx().doEnsure(this);
    }

    // Does the ref have a value?
    boolean isBound() {
        try {
            lock.readLock().lock();
            return tvals != null;
        } finally {
            lock.readLock().unlock();
        }
    }


    final public IFn fn() {
        return (IFn) deref();
    }

    public Object call() {
        return invoke();
    }

    public void run() {
        invoke();
    }

    public Object invoke() {
        return fn().invoke();
    }

    public Object invoke(Object arg1) {
        return fn().invoke(arg1);
    }

    public Object invoke(Object arg1, Object arg2) {
        return fn().invoke(arg1, arg2);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3) {
        return fn().invoke(arg1, arg2, arg3);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
        return fn().invoke(arg1, arg2, arg3, arg4);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16, Object arg17) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16, arg17);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16, Object arg17, Object arg18) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16, arg17, arg18);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16, arg17, arg18, arg19);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16, arg17, arg18, arg19, arg20);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                         Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                         Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20,
                         Object... args) {
        return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                arg16, arg17, arg18, arg19, arg20, args);
    }

    public Object applyTo(ISeq arglist) {
        return AFn.applyToHelper(this, arglist);
    }

}
