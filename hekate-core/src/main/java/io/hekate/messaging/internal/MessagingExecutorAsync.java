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

package io.hekate.messaging.internal;

import io.hekate.core.internal.util.Utils;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

class MessagingExecutorAsync implements MessagingExecutor {
    private final DefaultMessagingWorker[] workers;

    private final int size;

    public MessagingExecutorAsync(ThreadFactory factory, int size, ScheduledExecutorService timer) {
        assert size > 0 : "Thread pool size must be above zero [size=" + size + ']';
        assert timer != null : "Timer is null.";

        this.size = size;

        workers = new DefaultMessagingWorker[size];

        for (int i = 0; i < size; i++) {
            workers[i] = new DefaultMessagingWorker(factory, timer);
        }
    }

    @Override
    public MessagingWorker workerFor(int affinity) {
        return workers[Utils.mod(affinity, size)];
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public void terminate() {
        for (DefaultMessagingWorker worker : workers) {
            worker.execute(worker::shutdown);
        }
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        for (DefaultMessagingWorker worker : workers) {
            worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public int getThreadPoolSize() {
        return size;
    }
}