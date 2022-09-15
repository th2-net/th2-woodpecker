/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.woodpecker

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.Event.Status.FAILED
import com.exactpro.th2.common.event.Event.Status.PASSED
import com.exactpro.th2.common.event.EventUtils.createMessageBean
import com.exactpro.th2.common.event.IBodyData
import org.apache.commons.lang3.exception.ExceptionUtils.getMessage

fun infoEvent(event: String, description: IBodyData? = null): Event = Event.start().apply {
    name(event)
    type("info")
    status(PASSED)

    if (description != null) bodyData(description)
}

fun errorEvent(event: String, cause: Throwable? = null): Event = Event.start().apply {
    name(event)
    type("error")
    status(FAILED)

    generateSequence(cause, Throwable::cause).forEach {
        bodyData(createMessageBean(getMessage(it)))
    }
}