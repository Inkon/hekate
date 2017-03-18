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

package io.hekate.task;

import io.hekate.util.HekateFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asynchronous result of a {@link TaskService} operation.
 *
 * @param <T> Task result type.
 *
 * @see TaskService
 */
public class TaskFuture<T> extends HekateFuture<T, TaskFuture<T>> {
    @Override
    public T get() throws InterruptedException, TaskFutureException {
        try {
            return super.get();
        } catch (ExecutionException e) {
            throw new TaskFutureException(e.getCause());
        }
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, TaskFutureException, TimeoutException {
        try {
            return super.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new TaskFutureException(e.getCause());
        }
    }

    @Override
    public T getUninterruptedly() throws TaskFutureException {
        try {
            return super.getUninterruptedly();
        } catch (ExecutionException e) {
            throw new TaskFutureException(e.getCause());
        }
    }

    @Override
    protected TaskFuture<T> newInstance() {
        return new TaskFuture<>();
    }
}