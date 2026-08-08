package com.lanplay.player.core.concurrent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 key 复用锁，并把等待者计入引用数。只有持有者和等待者都离开后才移除条目，
 * 避免旧锁仍有人等待时新调用者创建第二把锁。
 */
class KeyedLockRegistry<K> {
    private class Entry {
        val monitor = Any()
        var users = 0
    }

    private val entries = ConcurrentHashMap<K, Entry>()

    fun <T> withLock(key: K, block: () -> T): T {
        val entry = entries.compute(key) { _, current ->
            (current ?: Entry()).also { it.users++ }
        }!!
        return try {
            synchronized(entry.monitor) { block() }
        } finally {
            entries.compute(key) { _, current ->
                if (current !== entry) current
                else if (--entry.users == 0) null else entry
            }
        }
    }

    internal fun activeKeyCount(): Int = entries.size
}

/** suspend 版本，语义与 [KeyedLockRegistry] 相同。 */
class KeyedMutexRegistry<K> {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = ConcurrentHashMap<K, Entry>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val entry = entries.compute(key) { _, current ->
            (current ?: Entry()).also { it.users++ }
        }!!
        return try {
            entry.mutex.withLock { block() }
        } finally {
            entries.compute(key) { _, current ->
                if (current !== entry) current
                else if (--entry.users == 0) null else entry
            }
        }
    }

    internal fun activeKeyCount(): Int = entries.size
}
