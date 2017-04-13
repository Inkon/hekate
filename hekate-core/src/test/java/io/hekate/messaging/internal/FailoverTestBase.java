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

import io.hekate.failover.FailoverContext;
import io.hekate.messaging.MessagingChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public abstract class FailoverTestBase extends MessagingServiceTestBase {
    protected final AtomicInteger failures = new AtomicInteger();

    protected TestChannel sender;

    protected TestChannel receiver;

    protected MessagingChannel<String> toSelf;

    protected MessagingChannel<String> toRemote;

    public FailoverTestBase(MessagingTestContext ctx) {
        super(ctx);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        List<TestChannel> channels = createAndJoinChannels(2, c -> c.withReceiver(msg -> {
            if (failures.getAndDecrement() > 0) {
                throw TEST_ERROR;
            }

            if (msg.mustReply()) {
                msg.reply(msg.get() + "reply");
            }
        }));

        awaitForChannelsTopology(channels);

        sender = channels.get(0);

        receiver = channels.get(1);

        toSelf = sender.get().forNode(sender.getNodeId());
        toRemote = sender.get().forNode(receiver.getNodeId());
    }
}
