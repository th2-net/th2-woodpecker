/*
 * Copyright 2021-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.woodpecker.api

import com.exactpro.th2.common.grpc.MessageGroup
import javax.annotation.concurrent.ThreadSafe

data class OnStartParameters(val settings: IMessageGeneratorSettings?, val rootEventId: String)

@ThreadSafe // methods will be called from different threads, but each method will be called from a single thread only
interface IMessageGenerator<S : IMessageGeneratorSettings> : AutoCloseable {
    fun onStart(parameters: OnStartParameters?) = Unit
    fun onNext(): MessageGroup
    fun onResponse(message: MessageGroup) = Unit
    fun onStop() = Unit
    override fun close() = Unit
}