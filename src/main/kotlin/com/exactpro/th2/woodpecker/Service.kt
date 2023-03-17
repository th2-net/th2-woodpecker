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

package com.exactpro.th2.woodpecker

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.woodpecker.api.IMessageGeneratorSettings
import com.exactpro.th2.woodpecker.grpc.Response
import com.exactpro.th2.woodpecker.grpc.Response.Status.FAILURE
import com.exactpro.th2.woodpecker.grpc.Response.Status.SUCCESS
import com.exactpro.th2.woodpecker.grpc.ScheduleRequest
import com.exactpro.th2.woodpecker.grpc.ScheduleRequest.Step
import com.exactpro.th2.woodpecker.grpc.StartRequest
import com.exactpro.th2.woodpecker.grpc.StopRequest
import com.exactpro.th2.woodpecker.grpc.WoodpeckerGrpc.WoodpeckerImplBase
import io.grpc.Status
import io.grpc.stub.StreamObserver
import mu.KotlinLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.locks.LockSupport

class Service(
    private val tickRate: Int,
    private val maxBatchSize: Int,
    private val readSettings: (String) -> IMessageGeneratorSettings,
    private val onStart: (IMessageGeneratorSettings?) -> Unit,
    private val onNext: (Int) -> MessageGroupBatch,
    private val onStop: () -> Unit,
    private val onBatch: (MessageGroupBatch) -> Unit,
    private val onEvent: (Event, EventID?) -> Unit,
) : WoodpeckerImplBase(), AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var future: Future<*> = CompletableFuture.completedFuture(null)
    @Volatile private var parentEventId: EventID? = null

    init {
        check(tickRate > 0) { "Invalid ${::tickRate.name} (<= 0): $tickRate" }
        check(maxBatchSize > 0) { "Invalid ${::maxBatchSize.name} (<= 0): $maxBatchSize" }
    }

    @Synchronized
    override fun start(request: StartRequest, observer: StreamObserver<Response>) = observer {
        val rate = request.rate
        val tickRate = request.tickRate.takeIf { it > 0 } ?: tickRate
        val maxBatchSize = request.maxBatchSize.takeIf { it > 0 } ?: maxBatchSize
        val settings = runCatching { request.settings.readSettings() }

        when {
            rate < tickRate -> failure("Rate is less than tick-rate ($tickRate): $rate mps")
            !future.isDone -> failure("Load is already running")
            settings.isFailure -> failure("Cannot load settings: ${settings.exceptionOrNull()?.message}")
            else -> with(settings.getOrNull()) {
                onStart(this)
                parentEventId = request.eventIdOrNull
                future = executor.scheduleWithMinDelay(1, SECONDS) { generateLoad(rate, tickRate, maxBatchSize) }
                success("Started load at constant rate - rate: $rate mps, tick-rate: $tickRate, max batch size: $maxBatchSize", SettingsBody(this))
            }
        }
    }

    @Synchronized
    override fun schedule(request: ScheduleRequest, observer: StreamObserver<Response>) = observer {
        val cycles = request.cycles
        val steps = request.stepsList
        val invalidStep = steps.firstOrNull { step -> step.duration < 1 || step.rate < (step.tickRate.takeIf { it > 0 } ?: tickRate) }
        val invalidSettings = steps.map { it to it.settings.runCatching { readSettings() }.exceptionOrNull() }.firstOrNull { it.second != null }

        when {
            cycles < 1 -> failure("Amount of cycles is less than 1: $cycles")
            steps.isEmpty() -> failure("There are no steps")
            invalidStep != null -> failure("Invalid step (duration < 1s or rate < tick-rate): ${invalidStep.toHuman()}")
            invalidSettings != null -> failure("Cannot load step (${invalidSettings.first.toHuman()}) settings: ${invalidSettings.second?.message}")
            !future.isDone -> failure("Load is already running")
            else -> {
                parentEventId = request.eventIdOrNull
                future = executor.submit { generateLoad(steps, cycles) }
                success("Started $cycles cycles of load steps: ${steps.toHuman()}")
            }
        }
    }

    @Synchronized
    override fun stop(request: StopRequest, observer: StreamObserver<Response>) = observer {
        when {
            future.isDone -> failure("Load is already stopped")
            !future.cancel(true) -> failure("Failed to stop load")
            else -> success("Successfully stopped load").also {
                onStop()
                parentEventId = null
            }
        }
    }

    override fun close() {
        logger.info { "Closing" }

        executor.runCatching {
            shutdown()

            if (!awaitTermination(CLOSE_TIMEOUT_MS, MILLISECONDS)) {
                logger.warn { "Failed to shutdown executor in $CLOSE_TIMEOUT_MS ms" }
                shutdownNow()
            }
        }

        logger.info { "Closed" }
    }

    private fun generateLoad(steps: List<Step>, cycles: Int) {
        repeat(cycles) { cycle ->
            if (future.isCancelled) return

            onInfo("Started load cycle ${cycle + 1}")

            steps.forEach { step ->
                if (future.isCancelled) return

                val settings = step.settings.readSettings()

                settings.runCatching(onStart).getOrElse {
                    onError("Failed to execute onStart handler", it)
                    throw it
                }

                onInfo("Started load step - ${step.toHuman()}", SettingsBody(settings))

                repeat(step.duration) {
                    if (future.isCancelled) return
                    val tickRate = step.tickRate.takeIf { it > 0 } ?: tickRate
                    val maxBatchSize = step.maxBatchSize.takeIf { it > 0 } ?: maxBatchSize
                    generateLoad(step.rate, tickRate, maxBatchSize)
                }

                onInfo("Finished load step - ${step.toHuman()}")

                runCatching(onStop).getOrElse {
                    onError("Failed to execute onStop handler", it)
                    throw it
                }
            }

            onInfo("Finished load cycle ${cycle + 1}")
        }

        onInfo("Load sequence has been completed")
    }

    private fun generateBatch(size: Int) = runCatching {
        onBatch(onNext(size))
    }.getOrElse {
        onError("Failed to send $size messages", it)
        throw it
    }

    private fun generateLoad(rate: Int, tickRate: Int, maxBatchSize: Int) = repeat(tickRate) {
        if (future.isCancelled) return
        val deadline = System.nanoTime() + SECONDS.toNanos(1) / tickRate
        val messages = rate / tickRate
        repeat(messages / maxBatchSize) { generateBatch(maxBatchSize) }
        val messagesLeft = messages % maxBatchSize
        if (messagesLeft > 0) generateBatch(messagesLeft)
        while (deadline > System.nanoTime()) LockSupport.parkNanos(deadline - System.nanoTime())
    }

    private fun onInfo(event: String, description: IBodyData? = null) {
        logger.info(event)
        onEvent(infoEvent(event, description), parentEventId)
    }

    private fun onError(event: String, cause: Throwable? = null) {
        logger.error(event, cause)
        onEvent(errorEvent(event, cause), parentEventId)
    }

    private fun success(event: String, description: IBodyData? = null): Response {
        onInfo(event, description)
        return Response.newBuilder().setStatus(SUCCESS).setMessage(event).build()
    }

    private fun failure(event: String): Response {
        onError(event)
        return Response.newBuilder().setStatus(FAILURE).setMessage(event).build()
    }

    private operator fun StreamObserver<Response>.invoke(block: () -> Response) = try {
        onNext(block())
        onCompleted()
    } catch (e: Exception) {
        onError("Failed to serve request", e)
        onError(Status.INTERNAL.withDescription(e.message).withCause(e).asException())
    }

    private fun String?.readSettings() = if (isNullOrBlank()) null else readSettings(this)

    private data class SettingsBody(val settings: IMessageGeneratorSettings?) : IBodyData

    companion object {
        private const val CLOSE_TIMEOUT_MS = 5000L

        private fun Step.toHuman() = "rate: $rate mps, duration: $duration s, tick-rate: $tickRate, max batch size: $maxBatchSize"
        private fun List<Step>.toHuman() = joinToString { "(${it.toHuman()})" }

        private fun ScheduledExecutorService.scheduleWithMinDelay(
            delay: Long,
            unit: TimeUnit,
            task: Runnable,
        ): Future<*> = object : FutureTask<Any?>(task, null) {
            override fun run() {
                if (isCancelled) return
                val nextTriggerTime = System.nanoTime() + unit.toNanos(delay)
                if (runAndReset()) schedule(this, nextTriggerTime - System.nanoTime(), NANOSECONDS)
            }
        }.apply(::execute)

        private val StartRequest.eventIdOrNull: EventID?
            get() = if (hasParentEventId()) parentEventId else null

        private val ScheduleRequest.eventIdOrNull: EventID?
            get() = if (hasParentEventId()) parentEventId else null
    }
}
