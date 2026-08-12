package dev.starfect.quill.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closing a handle while other threads are using it must not free memory out from under them.
 *
 * This is a regression test for a crash that reached CI: `malloc(): unaligned tcache chunk
 * detected`, SIGABRT, no stack trace pointing anywhere useful. The controller cancelled its
 * derivation coroutines and then immediately freed the documents they were reading — but
 * cancellation is a request, and a coroutine inside a native call has no suspension point at which
 * to honour it, so the free landed while the engine was still parsing.
 *
 * A `closed` flag could not have prevented it. Checking the flag and making the downcall are two
 * separate steps, and the whole failure is a close landing between them.
 *
 * The contract these tests pin is: a call that begins before the close either completes or throws
 * [IllegalStateException]; it never reads freed memory. The failure mode of a regression here is the
 * JVM aborting rather than a red test, which is unpleasant but is also exactly the signal — a test
 * executor that dies with exit 134 is telling you the handle was freed while in use.
 */
class HandleLifetimeTest {

    private companion object {
        const val THREADS = 8
        const val TIMEOUT_SECONDS = 30L

        val SOURCE = buildString {
            appendLine("# Concurrency")
            appendLine()
            repeat(40) { index ->
                appendLine("## Section $index")
                appendLine()
                appendLine("Some prose with `code`, a [link](https://example.com) and **emphasis**.")
                appendLine()
                appendLine("```rust")
                appendLine("fn section_$index() -> usize { $index }")
                appendLine("```")
                appendLine()
            }
        }
    }

    @Test
    fun `closing a document while it is being parsed does not corrupt the heap`() {
        val engine = QuillEngine.create(darkTheme = true)
        engine.use {
            repeat(20) { attempt ->
                val document = engine.openDocument(SOURCE)
                val started = CountDownLatch(THREADS)
                val failure = AtomicReference<Throwable>()
                val completed = AtomicInteger()
                val rejected = AtomicInteger()

                val workers = List(THREADS) { worker ->
                    Thread({
                        started.countDown()
                        repeat(50) {
                            try {
                                // The calls the controller's derivation makes, which are the slow
                                // ones and therefore the ones a close is most likely to land inside.
                                when (worker % 4) {
                                    0 -> document.blocks()
                                    1 -> document.htmlDom()
                                    2 -> document.stats()
                                    else -> document.outline()
                                }
                                completed.incrementAndGet()
                            } catch (expected: IllegalStateException) {
                                // "this QuillDocument has been closed" — the documented outcome for
                                // a call that starts after the close.
                                rejected.incrementAndGet()
                            } catch (unexpected: Throwable) {
                                failure.compareAndSet(null, unexpected)
                            }
                        }
                    }, "handle-lifetime-$attempt-$worker")
                }

                workers.forEach(Thread::start)
                // Close once every worker is running, so the close lands in the middle of the work
                // rather than before it starts.
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "workers did not start")
                document.close()

                workers.forEach { it.join(TIMEOUT_SECONDS * 1_000) }
                workers.forEach { assertTrue(!it.isAlive, "a worker did not finish: ${it.name}") }

                assertNull(failure.get(), "a worker failed with something other than IllegalStateException")
                assertEquals(
                    THREADS * 50,
                    completed.get() + rejected.get(),
                    "some calls neither completed nor were rejected",
                )
            }
        }
    }

    @Test
    fun `closing the engine while it is highlighting does not corrupt the heap`() {
        repeat(20) { attempt ->
            val engine = QuillEngine.create(darkTheme = true)
            val started = CountDownLatch(THREADS)
            val failure = AtomicReference<Throwable>()

            val workers = List(THREADS) { worker ->
                Thread({
                    started.countDown()
                    repeat(50) {
                        try {
                            engine.highlightCode("fn main() { println!(\"$worker\"); }", "rust")
                        } catch (expected: IllegalStateException) {
                            // Expected once the engine is closed.
                        } catch (unexpected: Throwable) {
                            failure.compareAndSet(null, unexpected)
                        }
                    }
                }, "engine-lifetime-$attempt-$worker")
            }

            workers.forEach(Thread::start)
            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "workers did not start")
            engine.close()

            workers.forEach { it.join(TIMEOUT_SECONDS * 1_000) }
            workers.forEach { assertTrue(!it.isAlive, "a worker did not finish: ${it.name}") }
            assertNull(failure.get(), "a worker failed with something other than IllegalStateException")
        }
    }

    @Test
    fun `closing twice is a no-op rather than a double free`() {
        val engine = QuillEngine.create(darkTheme = true)
        val document = engine.openDocument("# Once\n")
        assertEquals(1, document.outline().size)

        document.close()
        document.close()
        engine.close()
        engine.close()
    }
}
