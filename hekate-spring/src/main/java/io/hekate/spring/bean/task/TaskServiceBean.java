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

package io.hekate.spring.bean.task;

import io.hekate.spring.bean.HekateBaseBean;
import io.hekate.task.TaskService;

/**
 * Imports {@link TaskService} into a Spring context.
 */
public class TaskServiceBean extends HekateBaseBean<TaskService> {
    @Override
    public TaskService getObject() throws Exception {
        return getSource().tasks();
    }

    @Override
    public Class<TaskService> getObjectType() {
        return TaskService.class;
    }
}
