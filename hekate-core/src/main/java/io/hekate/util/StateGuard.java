/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.util;

import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Helper class that provides support for components lifecycle management and locking.
 *
 * <p>
 * Internally this class is backed by {@link ReentrantReadWriteLock}.
 * </p>
 *
 * @see StampedStateGuard
 */
public class StateGuard {
    /**
     * Enumeration of {@link StateGuard} states.
     */
    public enum State {
        /** See {@link StateGuard#becomeInitializing()}. */
        INITIALIZING,

        /** See {@link StateGuard#becomeInitialized()}. */
        INITIALIZED,

        /** See {@link StateGuard#becomeTerminating()}. */
        TERMINATING,

        /** See {@link StateGuard#becomeTerminated()}. */
        TERMINATED
    }

    private final Class<?> type;

    @ToStringIgnore
    private final ReentrantReadWriteLock.ReadLock readLock;

    @ToStringIgnore
    private final ReentrantReadWriteLock.WriteLock writeLock;

    private State state = State.TERMINATED;

    /**
     * Constructs new instance.
     *
     * @param type Type of a guarded component (for errors reporting).
     */
    public StateGuard(Class<?> type) {
        assert type != null : "Type is null.";

        this.type = type;

        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        readLock = lock.readLock();
        writeLock = lock.writeLock();
    }

    /**
     * Switches this guard to {@link State#INITIALIZING} state.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} being held.
     * </p>
     */
    public void becomeInitializing() {
        assert writeLock.isHeldByCurrentThread() : "Thread must hold write lock.";

        if (state == State.INITIALIZING || state == State.INITIALIZED) {
            throw new IllegalStateException(type.getSimpleName() + " is already in " + state + " state.");
        }

        state = State.INITIALIZING;
    }

    /**
     * Returns {@code true} if this guard is in {@link State#INITIALIZING} state.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} or {@link #lockRead() read lock} being held.
     * </p>
     *
     * @return {@code true} if this guard is in {@link State#INITIALIZING} state.
     */
    public boolean isInitializing() {
        return state == State.INITIALIZING;
    }

    /**
     * Switches this guard to {@link State#INITIALIZED} state.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} being held.
     * </p>
     */
    public void becomeInitialized() {
        assert writeLock.isHeldByCurrentThread() : "Thread must hold write lock.";

        if (state == State.INITIALIZED) {
            throw new IllegalStateException(type.getSimpleName() + " already initialized.");
        }

        state = State.INITIALIZED;
    }

    /**
     * Returns {@code true} if this guard is in {@link State#INITIALIZED} state.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} or {@link #lockRead() read lock} being held.
     * </p>
     *
     * @return {@code true} if this guard is in {@link State#INITIALIZED} state.
     */
    public boolean isInitialized() {
        return state == State.INITIALIZED;
    }

    /**
     * Switches this guard to {@link State#TERMINATING} state if guard is currently in {@link State#INITIALIZING} or {@link
     * State#INITIALIZED} state.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} being held.
     * </p>
     *
     * @return {@code true} if successfully switched to {@link State#TERMINATING} state.
     */
    public boolean becomeTerminating() {
        assert writeLock.isHeldByCurrentThread() : "Thread must hold write lock.";

        if (state != State.TERMINATED && state != State.TERMINATING) {
            state = State.TERMINATING;

            return true;
        }

        return false;
    }

    /**
     * Switches this guard to {@link State#TERMINATED} state if guard is currently in any other state besides {@link State#TERMINATED}.
     *
     * <p>
     * <b>Important!!!</b> This method must be invoked with {@link #lockWrite() write lock} being held.
     * </p>
     *
     * @return {@code true} if successfully switched to {@link State#TERMINATED} state.
     */
    public boolean becomeTerminated() {
        assert writeLock.isHeldByCurrentThread() : "Thread must hold write lock.";

        if (state != State.TERMINATED) {
            state = State.TERMINATED;

            return true;
        }

        return false;
    }

    /**
     * Acquires the read lock on this guard and checks its current state. If state is anything other than {@link State#INITIALIZED} then
     * {@link IllegalStateException} will be thrown and lock will be released.
     *
     * @throws IllegalStateException If guard is not in {@link State#INITIALIZED} state.
     */
    public void lockReadWithStateCheck() {
        readLock.lock();

        if (state != State.INITIALIZED) {
            readLock.unlock();

            throw new IllegalStateException(type.getSimpleName() + " is not initialized.");
        }
    }

    /**
     * Acquires the read lock on this guard and checks its current state. If state is anything other than the specified {@code state} then
     * {@link IllegalStateException} will be thrown and lock will be released.
     *
     * @param state Expected state.
     *
     * @throws IllegalStateException If the specified {@code state} doesn't match the current state of this guard.
     */
    public void lockReadWithStateCheck(State state) {
        readLock.lock();

        if (this.state != state) {
            readLock.unlock();

            throw new IllegalStateException(type.getSimpleName() + " is not in " + state + " state.");
        }
    }

    /**
     * Tries to acquire the read lock on this guard only if current state is {@link State#INITIALIZED}.
     *
     * @return {@code true} if lock was acquired.
     */
    public boolean tryLockReadWithStateCheck() {
        readLock.lock();

        if (state != State.INITIALIZED) {
            readLock.unlock();

            return false;
        }

        return true;
    }

    /**
     * Tries to acquire the read lock on this guard only if current state is the same with the specified one.
     *
     * @param state Expected state.
     *
     * @return {@code true} if lock was acquired.
     */
    public boolean tryLockReadWithStateCheck(State state) {
        readLock.lock();

        if (this.state != state) {
            readLock.unlock();

            return false;
        }

        return true;
    }

    /**
     * Acquires the read lock.
     *
     * @see ReentrantReadWriteLock.ReadLock#lock()
     */
    public void lockRead() {
        readLock.lock();
    }

    /**
     * Tries to acquire the read lock with the specified timeout.
     *
     * @param timeout Timeout.
     * @param unit Unit of timeout.
     *
     * @return {@code true} if lock was successfully acquired.
     *
     * @throws InterruptedException If thread was interrupted while awaiting for lock acquisition.
     * @see ReentrantReadWriteLock.ReadLock#tryLock(long, TimeUnit)
     */
    public boolean tryLockRead(long timeout, TimeUnit unit) throws InterruptedException {
        return readLock.tryLock(timeout, unit);
    }

    /**
     * Releases the read lock.
     *
     * @see ReentrantReadWriteLock.ReadLock#unlock()
     */
    public void unlockRead() {
        readLock.unlock();
    }

    /**
     * Acquires the write lock on this guard and checks its current state. If state is anything other than {@link State#INITIALIZED} then
     * {@link IllegalStateException} will be thrown and lock will be released.
     *
     * @throws IllegalStateException If guard is not in {@link State#INITIALIZED} state.
     */
    public void lockWriteWithStateCheck() {
        writeLock.lock();

        if (state != State.INITIALIZED) {
            writeLock.unlock();

            throw new IllegalStateException(type.getSimpleName() + " is not initialized.");
        }
    }

    /**
     * Acquires the write lock.
     *
     * @see ReentrantReadWriteLock.WriteLock#lock()
     */
    public void lockWrite() {
        writeLock.lock();
    }

    /**
     * Tries to acquire the write lock with the specified timeout.
     *
     * @param timeout Timeout.
     * @param unit Unit of timeout.
     *
     * @return {@code true} if lock was successfully acquired.
     *
     * @throws InterruptedException If thread was interrupted while awaiting for lock acquisition.
     * @see ReentrantReadWriteLock.WriteLock#tryLock(long, TimeUnit)
     */
    public boolean tryLockWrite(long timeout, TimeUnit unit) throws InterruptedException {
        return writeLock.tryLock(timeout, unit);
    }

    /**
     * Releases the write lock.
     *
     * @see ReentrantReadWriteLock.WriteLock#unlock()
     */
    public void unlockWrite() {
        writeLock.unlock();
    }

    /**
     * Returns {@code true} if current thread hold the write lock.
     *
     * @return {@code true} if current thread hold the write lock.
     *
     * @see ReentrantReadWriteLock.WriteLock#isHeldByCurrentThread()
     */
    public boolean isWriteLocked() {
        return writeLock.isHeldByCurrentThread();
    }

    @Override
    public String toString() {
        readLock.lock();

        try {
            return ToString.format(this);
        } finally {
            readLock.unlock();
        }
    }
}