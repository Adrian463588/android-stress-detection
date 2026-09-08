package com.stressdetector.mobile.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTaskExecutorTest {
    @Test
    fun rejectsWorkBeyondFixedCapacityWithoutBlockingCaller() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = BoundedTaskExecutor(capacity = 2, threadName = "bounded-test")

        assertTrue(
            executor.submit {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        )
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(executor.submit {})
        assertTrue(executor.submit {})
        assertFalse(executor.submit {})
        release.countDown()
    }
}
