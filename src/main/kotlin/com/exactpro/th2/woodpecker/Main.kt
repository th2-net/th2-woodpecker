/*
 * Copyright 2021-2023 Exactpro (Exactpro Systems Limited)
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

@file:JvmName("Main")

package com.exactpro.th2.woodpecker

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.metrics.LIVENESS_MONITOR
import com.exactpro.th2.common.metrics.READINESS_MONITOR
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.TransportGroupBatchRouter
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.woodpecker.api.IGeneratorFactory
import com.exactpro.th2.woodpecker.api.IGeneratorSettings
import com.exactpro.th2.woodpecker.api.impl.EventGeneratorContext
import com.exactpro.th2.woodpecker.api.impl.MessageGeneratorContext
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature.NullIsSameAsDefault
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import javax.naming.OperationNotSupportedException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger {}

private const val MSG_GROUP_QUEUE_ATTRIBUTE = "protobuf-group"
private val INPUT_MSG_GROUP_QUEUE_ATTRIBUTE = arrayOf(MSG_GROUP_QUEUE_ATTRIBUTE, "in")
private val OUTPUT_MSG_GROUP_QUEUE_ATTRIBUTE = arrayOf(MSG_GROUP_QUEUE_ATTRIBUTE, "out")
private val OUTPUT_TRANSPORT_MSG_QUEUE_ATTRIBUTE = arrayOf(TransportGroupBatchRouter.TRANSPORT_GROUP_ATTRIBUTE, "out")

private const val EVENT_BATCH_QUEUE_ATTRIBUTE = "event"
private val INPUT_EVENT_BATCH_QUEUE_ATTRIBUTE = arrayOf(EVENT_BATCH_QUEUE_ATTRIBUTE, "in")
private val OUTPUT_EVENT_BATCH_QUEUE_ATTRIBUTE = arrayOf(EVENT_BATCH_QUEUE_ATTRIBUTE, "publish")

fun main(args: Array<String>) = try {
    LIVENESS_MONITOR.enable()
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val commonFactory = CommonFactory.createFromArguments(*args).apply { resources += "factory" to ::close }
    val eventRouter = commonFactory.eventBatchRouter
    val messageGroupRouter = commonFactory.messageRouterMessageGroupBatch
    val transportMessageRouter = commonFactory.transportGroupBatchRouter
    val generatorFactory = load<IGeneratorFactory<IGeneratorSettings>>()

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().configure(NullIsSameAsDefault, true).build())
        .addModule(
            SimpleModule().addAbstractTypeMapping(
                IGeneratorSettings::class.java,
                generatorFactory.settingsClass
            )
        ).build()

    val onProtoBatch = { batch: MessageGroupBatch -> messageGroupRouter.sendAll(batch, *OUTPUT_MSG_GROUP_QUEUE_ATTRIBUTE) }
    val onProtoBatchEvent = { batch: EventBatch -> eventRouter.sendAll(batch, *OUTPUT_EVENT_BATCH_QUEUE_ATTRIBUTE) }
    val onTransportBatch = { batch: GroupBatch -> transportMessageRouter.sendAll(batch, *OUTPUT_TRANSPORT_MSG_QUEUE_ATTRIBUTE) }

    val onRequestMessage = { message: MessageGroup -> onProtoBatch(MessageGroupBatch.newBuilder().addGroups(message).build()) }
    val onRequestEvent = { eventBatch: EventBatch -> onProtoBatchEvent(eventBatch)}

    val settings = commonFactory.getCustomConfiguration(Settings::class.java, mapper)

    val messageGeneratorContext = MessageGeneratorContext(settings.generatorSettings, onRequestMessage, commonFactory::loadDictionary)
    val eventGeneratorContext = EventGeneratorContext(settings.generatorSettings, onRequestEvent)

    val messageGenerator = generatorFactory.createMessageGenerator(messageGeneratorContext).apply { resources += "messageGenerator" to ::close }
    val eventGenerator = generatorFactory.createEventGenerator(eventGeneratorContext).apply { resources += "eventGenerator" to ::close }


    runCatching {
        checkNotNull(messageGroupRouter.subscribe({ _, batch ->
            batch.groupsList.forEach(messageGenerator::onResponse)
        }, *INPUT_MSG_GROUP_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        resources += "subscriber-monitor" to monitor::unsubscribe
    }.onFailure {
        throw IllegalStateException("Failed to subscribe to input queue", it)
    }

    runCatching {
        checkNotNull(eventRouter.subscribe({ _, batch ->
            batch.eventsList.forEach(eventGenerator::onResponse)
        }, *INPUT_EVENT_BATCH_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        resources += "subscriber-monitor" to monitor::unsubscribe
    }.onFailure {
        throw IllegalStateException("Failed to subscribe to input queue", it)
    }

    val rootEventId: EventID = commonFactory.rootEventId

    val onEvent: (Event, EventID?) -> Unit = { event: Event, parentId: EventID? ->
        eventRouter.storeEvent(event, parentId ?: rootEventId)
    }


    val service = when (settings.useTransportMode) {
        WoodpeckerMode.MESSAGE_TRANSPORT_MODE -> {
            val onBatchProxy = createBatchProxy(settings, resources, onEvent, onTransportBatch)

            Service(
                settings.tickRate,
                settings.maxBatchSize,
                mapper::readValue,
                messageGenerator::onStart,
                messageGenerator::onNextTransport,
                messageGenerator::onStop,
                onBatchProxy,
                onEvent
            )
        }
        WoodpeckerMode.EVENT_MODE -> {
            val onBatchProxy = createBatchProxy(settings, resources, onEvent, onRequestEvent)

            Service(
                settings.tickRate,
                settings.maxBatchSize,
                mapper::readValue,
                eventGenerator::onStart,
                eventGenerator::onNext,
                eventGenerator::onStop,
                onBatchProxy,
                onEvent
            )
        }
        WoodpeckerMode.MESSAGE_MODE -> {
            val onBatchProxy = createBatchProxy(settings, resources, onEvent, onProtoBatch)

            Service(
                settings.tickRate,
                settings.maxBatchSize,
                mapper::readValue,
                messageGenerator::onStart,
                messageGenerator::onNext,
                messageGenerator::onStop,
                onBatchProxy,
                onEvent
            )
        }
        else -> {
            throw OperationNotSupportedException()
        }
    }.apply { resources += "service" to ::close }

    commonFactory.grpcRouter.startServer(service).run {
        start()
        READINESS_MONITOR.enable()
        LOGGER.info { "Successfully started" }
        awaitTermination()
        LOGGER.info { "Finished running" }
    }
} catch (e: Exception) {
    READINESS_MONITOR.disable()
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

private fun <T> createBatchProxy(
    settings: Settings,
    resources: Deque<Pair<String, () -> Unit>>,
    onEvent: (Event, EventID?) -> Unit,
    onBatch: (T) -> Unit
): (T) -> Unit = when (val size = settings.maxOutputQueueSize) {
    0 -> onBatch
    else -> LinkedBlockingQueue<T>(size).apply {
        resources += "sender" to thread(name = "sender") {
            while (!Thread.interrupted()) {
                take().runCatching(onBatch).getOrElse {
                    onEvent(errorEvent("Failed to send message batch", it), null)
                    LOGGER.error(it) { "Failed to send message batch" }
                }
            }
        }::interrupt
    }::put
}

data class Settings(
    val tickRate: Int = 10,
    val maxBatchSize: Int = 1000,
    val maxOutputQueueSize: Int = 0,
    val useTransportMode: WoodpeckerMode = WoodpeckerMode.MESSAGE_MODE,
    val generatorSettings: IGeneratorSettings,
) {
    init {
        require(tickRate > 0) { "${::tickRate.name} is less or equal to zero: $maxBatchSize" }
        require(maxBatchSize > 0) { "${::maxBatchSize.name} is less or equal to zero: $maxBatchSize" }
        require(maxOutputQueueSize >= 0) { "${::maxOutputQueueSize.name} is less than zero: $maxOutputQueueSize" }
    }
}

private inline fun <reified T> load(): T = ServiceLoader.load(T::class.java).toList().run {
    when (size) {
        0 -> error("No instances of ${T::class.simpleName}")
        1 -> first()
        else -> error("More than 1 instance of ${T::class.simpleName} has been found: $this")
    }
}
