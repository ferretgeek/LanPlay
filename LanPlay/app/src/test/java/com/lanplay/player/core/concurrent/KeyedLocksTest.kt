package com.lanplay.player.core.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyedLocksTest {
    @Test
    fun blockingRegistryNeverRunsSameKeyConcurrently() {
        val registry = KeyedLockRegistry<String>()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val done = CountDownLatch(100)
        repeat(100) {
            pool.execute {
                start.await()
                registry.withLock("same") {
                    val now = active.incrementAndGet()
                    maximum.accumulateAndGet(now) { old, value -> maxOf(old, value) }
                    Thread.yield()
                    active.decrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        check(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, maximum.get())
        assertEquals(0, registry.activeKeyCount())
    }

    @Test
    fun suspendRegistryNeverRunsSameKeyConcurrently() = runBlocking {
        val registry = KeyedMutexRegistry<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        (0 until 100).map {
            async(Dispatchers.Default) {
                registry.withLock("same") {
                    val now = active.incrementAndGet()
                    maximum.accumulateAndGet(now) { old, value -> maxOf(old, value) }
                    Thread.yield()
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maximum.get())
        assertEquals(0, registry.activeKeyCount())
    }
}
