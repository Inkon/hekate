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

package io.hekate.failover;

import java.util.List;

/**
 * Condition for {@link FailoverPolicyBuilder}.
 *
 * <p>
 * Implementations of this interface are responsible for deciding on whether a {@link FailoverPolicy} should be applied to a failed
 * operation.
 * </p>
 *
 * <p>
 * Instances of this interface can be registered via {@link FailoverPolicyBuilder#setRetryUntil(List)} method.
 * </p>
 *
 * @see FailoverPolicyBuilder
 */
public interface FailoverCondition {
    /**
     * Returns {@code true} if failover policy should be applied.
     *
     * @param failover Failover attempt info.
     *
     * @return {@code true} if failover policy should be applied.
     */
    boolean test(FailureInfo failover);
}