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

package io.hekate.core.internal.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class HekateThreadFactory implements ThreadFactory {
    public static final String THREAD_COMMON_PREFIX = "Hekate";

    private static final AtomicInteger FACTORY_COUNTER = new AtomicInteger();

    private final int factoryId = FACTORY_COUNTER.getAndIncrement();

    private final AtomicInteger threadCounter = new AtomicInteger();

    private final String nodeName;

    private final String threadName;

    public HekateThreadFactory(String threadName) {
        this(null, threadName);
    }

    public HekateThreadFactory(String nodeName, String threadName) {
        if ((nodeName == null || nodeName.isEmpty()) && Thread.currentThread() instanceof HekateThread) {
            HekateThread parent = (HekateThread)Thread.currentThread();

            nodeName = parent.getNodeName();
        }

        this.nodeName = nodeName == null ? "" : nodeName;
        this.threadName = threadName;
    }

    @Override
    public Thread newThread(Runnable r) {
        String name = THREAD_COMMON_PREFIX + threadName + '-' + factoryId + '-' + threadCounter.getAndIncrement();

        return new HekateThread(nodeName, name, r);
    }
}