package de.samthedev.lpbsa.config

import de.samthedev.lpbsa.message.MessageTemplates
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertFalse

class RuntimeStateStoreTest {
    @Test
    fun `concurrent readers only observe complete config and message snapshots`() {
        val first = state("first")
        val second = state("second")
        val store = RuntimeStateStore(first)
        val start = CountDownLatch(1)
        val mismatch = AtomicBoolean(false)
        val writer = thread {
            start.await()
            repeat(100_000) { store.replace(if (it and 1 == 0) second else first) }
        }
        val readers = List(4) {
            thread {
                start.await()
                repeat(100_000) {
                    val snapshot = store.current()
                    if (snapshot.config.globalBypassPermission != snapshot.messages.prefix) mismatch.set(true)
                }
            }
        }

        start.countDown()
        writer.join()
        readers.forEach(Thread::join)

        assertFalse(mismatch.get())
    }

    private fun state(marker: String) = RuntimeState(
        RuntimeConfig(
            1, false, DefaultPolicy.OPEN, marker, FailMode.CLOSED, false,
            DenialSettings(), LoggingSettings(), emptyMap(), emptyMap(), emptySet(),
        ),
        MessageTemplates(1, marker, emptyMap()),
    )
}
