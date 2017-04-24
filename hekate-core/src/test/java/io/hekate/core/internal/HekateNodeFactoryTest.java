/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http:www.apache.orglicensesLICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.core.internal;

import io.hekate.HekateTestBase;
import io.hekate.core.HekateBootstrap;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class HekateNodeFactoryTest extends HekateTestBase {
    @Test
    public void test() throws Exception {
        assertValidUtilityClass(HekateNodeFactory.class);

        assertNotNull(HekateNodeFactory.create(new HekateBootstrap()));
    }
}
