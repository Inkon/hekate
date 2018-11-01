package io.hekate.messaging.internal;

import io.hekate.messaging.unicast.ResponsePart;

interface MessageOperationCallback<T> {
    boolean completeAttempt(MessageOperationAttempt<T> attempt, ResponsePart<T> rsp, Throwable err);
}
