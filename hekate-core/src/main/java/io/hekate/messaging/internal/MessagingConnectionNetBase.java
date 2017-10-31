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

import io.hekate.core.internal.util.ErrorUtils;
import io.hekate.messaging.MessagingEndpoint;
import io.hekate.messaging.internal.MessagingProtocol.AffinityNotification;
import io.hekate.messaging.internal.MessagingProtocol.AffinityRequest;
import io.hekate.messaging.internal.MessagingProtocol.AffinityStreamRequest;
import io.hekate.messaging.internal.MessagingProtocol.ErrorResponse;
import io.hekate.messaging.internal.MessagingProtocol.FinalResponse;
import io.hekate.messaging.internal.MessagingProtocol.Notification;
import io.hekate.messaging.internal.MessagingProtocol.Request;
import io.hekate.messaging.internal.MessagingProtocol.ResponseChunk;
import io.hekate.messaging.internal.MessagingProtocol.StreamRequest;
import io.hekate.messaging.unicast.SendCallback;
import io.hekate.network.NetworkEndpoint;
import io.hekate.network.NetworkFuture;

abstract class MessagingConnectionNetBase<T> extends MessagingConnectionBase<T> {
    private final NetworkEndpoint<MessagingProtocol> net;

    private final SendPressureGuard pressureGuard;

    public MessagingConnectionNetBase(NetworkEndpoint<MessagingProtocol> net, MessagingGateway<T> gateway, MessagingEndpoint<T> endpoint) {
        super(gateway, gateway.async(), endpoint);

        assert net != null : "Endpoint is null.";

        this.net = net;
        this.pressureGuard = gateway.sendGuard();
    }

    @Override
    public NetworkFuture<MessagingProtocol> disconnect() {
        return net.disconnect();
    }

    @Override
    public void request(MessageRoute<T> route, InternalRequestCallback<T> callback, boolean retransmit) {
        MessageContext<T> ctx = route.ctx();

        RequestHandle<T> handle = registerRequest(ctx, callback);

        Request<T> msg;

        if (ctx.hasAffinity()) {
            msg = new AffinityRequest<>(ctx.affinity(), handle.id(), retransmit, route.preparePayload());
        } else {
            msg = new Request<>(handle.id(), retransmit, route.preparePayload());
        }

        msg.prepareSend(handle, this);

        net.send(msg, msg /* <-- Message itself is a callback.*/);
    }

    @Override
    public void stream(MessageRoute<T> route, InternalRequestCallback<T> callback, boolean retransmit) {
        MessageContext<T> ctx = route.ctx();

        RequestHandle<T> handle = registerRequest(ctx, callback);

        StreamRequest<T> msg;

        if (ctx.hasAffinity()) {
            msg = new AffinityStreamRequest<>(ctx.affinity(), handle.id(), retransmit, route.preparePayload());
        } else {
            msg = new StreamRequest<>(handle.id(), retransmit, route.preparePayload());
        }

        msg.prepareSend(handle, this);

        net.send(msg, msg /* <-- Message itself is a callback.*/);
    }

    @Override
    public void sendNotification(MessageRoute<T> route, SendCallback callback, boolean retransmit) {
        MessageContext<T> ctx = route.ctx();

        Notification<T> msg;

        if (ctx.hasAffinity()) {
            msg = new AffinityNotification<>(ctx.affinity(), retransmit, route.preparePayload());
        } else {
            msg = new Notification<>(retransmit, route.preparePayload());
        }

        msg.prepareSend(ctx.worker(), this, callback);

        net.send(msg, msg /* <-- Message itself is a callback.*/);
    }

    @Override
    public void replyChunk(MessagingWorker worker, int requestId, T chunk, SendCallback callback) {
        ResponseChunk<T> msg = new ResponseChunk<>(requestId, chunk);

        if (msg.prepareSend(worker, this, callback)) {
            net.send(msg, msg /* <-- Message itself is a callback.*/);
        }
    }

    @Override
    public void replyFinal(MessagingWorker worker, int requestId, T response, SendCallback callback) {
        FinalResponse<T> msg = new FinalResponse<>(requestId, response);

        msg.prepareSend(worker, this, callback);

        net.send(msg, msg /* <-- Message itself is a callback.*/);
    }

    @Override
    public void replyError(MessagingWorker worker, int requestId, Throwable cause) {
        net.send(new ErrorResponse(requestId, ErrorUtils.stackTrace(cause)));
    }

    public SendPressureGuard pressureGuard() {
        return pressureGuard;
    }

    @Override
    protected void disconnectOnError(Throwable t) {
        net.disconnect();
    }
}