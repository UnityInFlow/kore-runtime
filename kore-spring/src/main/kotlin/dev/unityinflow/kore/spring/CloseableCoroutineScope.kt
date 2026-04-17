package dev.unityinflow.kore.spring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineScope] that implements [AutoCloseable] by cancelling its [Job]
 * on [close]. This allows Spring to invoke `destroyMethod = "close"` at
 * context shutdown, ensuring the [Job] (and all child coroutines) are
 * cancelled when the bean is destroyed.
 */
class CloseableCoroutineScope(
    context: CoroutineContext,
) : CoroutineScope,
    AutoCloseable {
    override val coroutineContext: CoroutineContext = context

    override fun close() {
        coroutineContext.job.cancel()
    }
}
