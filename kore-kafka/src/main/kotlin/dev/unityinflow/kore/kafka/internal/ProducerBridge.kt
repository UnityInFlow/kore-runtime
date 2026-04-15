package dev.unityinflow.kore.kafka.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges Kafka's callback-based [KafkaProducer.send] into a suspending function.
 *
 * Per Kafka's callback contract, exactly one of (metadata, exception) is non-null.
 * The `!!` on `metadata` in the success branch is safe because that contract is
 * documented by Apache Kafka's producer API — the callback is never invoked with
 * both parameters null or both non-null (CLAUDE.md no-bang rule).
 *
 * Kafka has no in-flight cancel API; [suspendCancellableCoroutine]'s
 * `invokeOnCancellation` is intentionally a no-op — sends either complete or fail
 * quickly via their timeouts.
 */
internal suspend fun KafkaProducer<String, ByteArray>.awaitSend(record: ProducerRecord<String, ByteArray>): RecordMetadata =
    suspendCancellableCoroutine { cont ->
        send(record) { metadata, exception ->
            if (exception != null) {
                cont.resumeWithException(exception)
            } else {
                // Safe per Kafka callback contract: exactly one of (metadata, exception) is non-null.
                cont.resume(metadata!!)
            }
        }
    }
