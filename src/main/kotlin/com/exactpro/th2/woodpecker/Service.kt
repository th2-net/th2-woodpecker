/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.MessageGroup
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS

class Service(
    private val minBatchesPerSecond: Int,
    private val maxBatchSize: Int,
    private val readSettings: (String) -> IMessageGeneratorSettings,
    private val onStart: (IMessageGeneratorSettings?) -> Unit,
    private val onNext: () -> MessageGroup,
    private val onStop: () -> Unit,
    private val onBatch: (MessageGroupBatch) -> Unit,
    private val onEvent: (cause: Throwable?, type: String, message: () -> String) -> Unit,
) : WoodpeckerImplBase(), AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var future: Future<*> = CompletableFuture.completedFuture(null)

    init {
        check(minBatchesPerSecond > 0) { "Invalid ${::minBatchesPerSecond.name} (<= 0): $minBatchesPerSecond" }
        check(maxBatchSize > 0) { "Invalid ${::maxBatchSize.name} (<= 0): $maxBatchSize" }
    }

    @Synchronized
    override fun start(request: StartRequest, observer: StreamObserver<Response>) = observer {
        val rate = request.rate

        when {
            rate < 1 -> failure("Rate is less than 1: $rate mps")
            !future.isDone -> failure("Load is already running")
            else -> {
                onStart(request.settings.readSettings())
                future = executor.startLoad { rate / minBatchesPerSecond }
                success("Started load at constant rate: $rate mps")
            }
        }
    }

    @Synchronized
    override fun schedule(request: ScheduleRequest, observer: StreamObserver<Response>) = observer {
        val cycles = request.cycles
        val steps = request.stepsList
        val invalidSteps = steps.filter { it.duration < 1 || it.rate < 1 || it.settings.runCatching(readSettings).isFailure }

        when {
            cycles < 1 -> failure("Amount of cycles is less than 1: $cycles")
            steps.isEmpty() -> failure("There are no steps")
            invalidSteps.isNotEmpty() -> failure("Invalid steps (duration < 1s or rate < 1 mps or invalid settings): ${invalidSteps.toHuman()}")
            !future.isDone -> failure("Load is already running")
            else -> {
                future = executor.startLoad(steps.toRates(cycles)::next)
                success("Started $cycles cycles of load steps: ${steps.toHuman()}")
            }
        }
    }

    @Synchronized
    override fun stop(request: StopRequest, observer: StreamObserver<Response>) = observer {
        when {
            future.isDone -> failure("Load is already stopped")
            !future.cancel(true) -> failure("Failed to stop load")
            else -> success("Successfully stopped load").also { onStop() }
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

    private fun List<Step>.toRates(cycles: Int) = iterator {
        repeat(cycles) { cycle ->
            onInfo { "Started load cycle ${cycle + 1}" }

            forEach { step ->
                onStart(step.settings.readSettings())
                onInfo { "Started load step: ${step.toHuman()}" }
                repeat(step.duration * minBatchesPerSecond) { yield(step.rate / minBatchesPerSecond) }
                onInfo { "Finished load step: ${step.toHuman()}" }
                onStop()
            }

            onInfo { "Finished load cycle ${cycle + 1}" }
        }

        onInfo { "Load sequence has been completed" }
    }

    private fun sendBatch(size: Int) = MessageGroupBatch.newBuilder().runCatching {
        repeat(size) { addGroups(onNext()) }
        onBatch(build())
    }.getOrElse {
        onError(it) { "Failed to send $size messages" }
        throw it
    }

    private fun generateLoad(rate: () -> Int) = Runnable {
        try {
            rate().toSizes(maxBatchSize).forEach(::sendBatch)
        } catch (e: Exception) {
            onError(e) { "Failed to generate load" }
            throw e
        }
    }

    private fun ScheduledExecutorService.startLoad(rate: () -> Int) = scheduleAtFixedRate(
        generateLoad(rate),
        1000L / minBatchesPerSecond,
        1000L / minBatchesPerSecond,
        MILLISECONDS
    )

    private fun onInfo(message: () -> String) {
        logger.info(message)
        onEvent(null, "Info", message)
    }

    private fun onError(cause: Throwable? = null, message: () -> String) {
        logger.error(cause, message)
        onEvent(cause, "Error", message)
    }

    private fun success(message: String): Response {
        onInfo { message }
        return Response.newBuilder().setStatus(SUCCESS).setMessage(message).build()
    }

    private fun failure(message: String): Response {
        onError { message }
        return Response.newBuilder().setStatus(FAILURE).setMessage(message).build()
    }

    private operator fun StreamObserver<Response>.invoke(block: () -> Response) = try {
        onNext(block())
        onCompleted()
    } catch (e: Exception) {
        onError(e) { "Failed to serve request" }
        onError(Status.INTERNAL.withDescription(e.message).withCause(e).asException())
    }

    private fun String?.readSettings() = if (isNullOrBlank()) null else readSettings(this)

    companion object {
        private const val CLOSE_TIMEOUT_MS = 5000L

        private fun Step.toHuman() = "${duration}s at $rate mps"
        private fun List<Step>.toHuman() = joinToString { it.toHuman() }

        private fun Int.toSizes(maxSize: Int) = iterator {
            repeat(this@toSizes / maxSize) { yield(maxSize) }
            val rest = this@toSizes % maxSize
            if (rest > 0) yield(rest)
        }
    }
}