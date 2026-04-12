package dev.unityinflow.kore.observability

import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KoreTracerTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var tracer: KoreTracer

    @BeforeEach
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        tracer = KoreTracer(provider.get("kore-observability-test"))
    }

    @Test
    fun `withSpan creates a span with the exact name supplied`() =
        runTest {
            tracer.withSpan("kore.agent.run") { }

            val spans = exporter.finishedSpanItems
            spans.size shouldBe 1
            spans[0].name shouldBe "kore.agent.run"
        }

    @Test
    fun `withSpan calls end() even if block throws`() =
        runTest {
            try {
                tracer.withSpan("kore.agent.run") {
                    throw RuntimeException("simulated failure")
                }
            } catch (_: RuntimeException) {
                // expected
            }

            val spans = exporter.finishedSpanItems
            spans.size shouldBe 1
            spans[0].name shouldBe "kore.agent.run"
        }

    @Test
    fun `child span traceId matches parent span traceId when nested`() =
        runTest {
            tracer.withSpan("kore.agent.run") { _ ->
                tracer.withSpan("kore.llm.call") { }
            }

            val spans = exporter.finishedSpanItems
            spans.size shouldBe 2

            val parentSpan = spans.first { it.name == "kore.agent.run" }
            val childSpan = spans.first { it.name == "kore.llm.call" }

            childSpan.traceId shouldBe parentSpan.traceId
        }

    @Test
    fun `withSpan sets attributes on span`() =
        runTest {
            tracer.withSpan(
                name = "kore.agent.run",
                attrs =
                    mapOf(
                        KoreAttrs.AGENT_ID to "agent-123",
                        KoreAttrs.AGENT_NAME to "test-agent",
                    ),
            ) { }

            val span = exporter.finishedSpanItems.single()
            span.attributes.get(AttributeKey.stringKey(KoreAttrs.AGENT_ID)) shouldBe "agent-123"
            span.attributes.get(AttributeKey.stringKey(KoreAttrs.AGENT_NAME)) shouldBe "test-agent"
        }

    @Test
    fun `withSpan uses INTERNAL kind by default`() =
        runTest {
            tracer.withSpan("kore.agent.run") { }

            val span = exporter.finishedSpanItems.single()
            span.kind shouldBe SpanKind.INTERNAL
        }
}
