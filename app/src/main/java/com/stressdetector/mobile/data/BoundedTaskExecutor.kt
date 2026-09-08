package com.stressdetector.mobile.data

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val MIN_AWAIT_TIMEOUT_MILLIS = 1L
private const val MAX_AWAIT_TIMEOUT_MILLIS = 5_000L

/** One ordered, application-scoped worker with explicit backpressure. */
internal class BoundedTaskExecutor(capacity: Int, threadName: String) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { task -> Thread(task, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun awaitIdle(timeoutMillis: Long): Boolean {
        val barrier = CountDownLatch(1)
        if (!submit { barrier.countDown() }) return false
        return barrier.await(
            timeoutMillis.coerceIn(MIN_AWAIT_TIMEOUT_MILLIS, MAX_AWAIT_TIMEOUT_MILLIS),
            TimeUnit.MILLISECONDS
        )
    }
}
