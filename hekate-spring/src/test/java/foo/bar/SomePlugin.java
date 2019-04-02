/*
 * Copyright 2019 The Hekate Project
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

package foo.bar;

import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.core.HekateException;
import io.hekate.core.plugin.Plugin;

public class SomePlugin implements Plugin {
    @Override
    public void install(HekateBootstrap boot) {
        // No-op.
    }

    @Override
    public void start(Hekate hekate) throws HekateException {
        // No-op.
    }

    @Override
    public void stop() throws HekateException {
        // No-op.
    }
}
