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

@file:JvmName("Main")

package com.exactpro.th2.woodpecker

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.metrics.liveness
import com.exactpro.th2.common.metrics.readiness
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.woodpecker.api.IMessageGeneratorFactory
import com.exactpro.th2.woodpecker.api.IMessageGeneratorSettings
import com.exactpro.th2.woodpecker.api.impl.MessageGeneratorContext
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger {}

private const val INPUT_QUEUE_ATTRIBUTE = "in"
private const val OUTPUT_QUEUE_ATTRIBUTE = "out"

fun main(args: Array<String>) = try {
    liveness = true
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
    val messageRouter = commonFactory.messageRouterMessageGroupBatch
    val generatorFactory = load<IMessageGeneratorFactory<IMessageGeneratorSettings>>()

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule(nullIsSameAsDefault = true))
        .addModule(SimpleModule().addAbstractTypeMapping(IMessageGeneratorSettings::class.java, generatorFactory.settingsClass))
        .build()

    val onBatch = { batch: MessageGroupBatch -> messageRouter.sendAll(batch, OUTPUT_QUEUE_ATTRIBUTE) }
    val onRequest = { message: MessageGroup -> onBatch(MessageGroupBatch.newBuilder().addGroups(message).build()) }
    val settings = commonFactory.getCustomConfiguration(Settings::class.java, mapper)
    val context = MessageGeneratorContext(settings.generatorSettings, onRequest, commonFactory::loadDictionary)
    val generator = generatorFactory.createGenerator(context).apply { resources += "generator" to ::close }

    runCatching {
        checkNotNull(messageRouter.subscribe({ _, batch ->
            batch.groupsList.forEach(generator::onResponse)
        }, INPUT_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        resources += "subscriber-monitor" to monitor::unsubscribe
    }.onFailure {
        throw IllegalStateException("Failed to subscribe to input queue", it)
    }

    val rootEventId = eventRouter.storeEvent(Event.start().apply {
        name("Woodpecker '${generator::class.simpleName}' ${Instant.now()}")
        type("Microservice")
    }).id

    val onEvent = { cause: Throwable?, type: String, message: () -> String ->
        eventRouter.storeEvent(rootEventId, message(), type, cause)
    }

    val onBatchProxy = when (val size = settings.maxOutputQueueSize) {
        0 -> onBatch
        else -> LinkedBlockingQueue<MessageGroupBatch>(size).apply {
            resources += "sender" to thread(name = "sender") {
                while (!Thread.interrupted()) {
                    take().runCatching(onBatch).getOrElse {
                        onEvent(it, "Error") { "Failed to send message batch" }
                        LOGGER.error(it) { "Failed to send message batch" }
                    }
                }
            }::interrupt
        }::put
    }

    val service = Service(
        settings.tickRate,
        settings.maxBatchSize,
        mapper::readValue,
        generator::onStart,
        generator::onNext,
        generator::onStop,
        onBatchProxy,
        onEvent,
        rootEventId
    ).apply { resources += "service" to ::close }

    commonFactory.grpcRouter.startServer(service).run {
        start()
        readiness = true
        LOGGER.info { "Successfully started" }
        awaitTermination()
        LOGGER.info { "Finished running" }
    }
} catch (e: Exception) {
    readiness = false
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

data class Settings(
    val tickRate: Int = 10,
    val maxBatchSize: Int = 1000,
    val maxOutputQueueSize: Int = 0,
    val generatorSettings: IMessageGeneratorSettings,
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
