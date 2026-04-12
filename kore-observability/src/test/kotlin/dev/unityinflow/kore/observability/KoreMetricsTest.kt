package dev.unityinflow.kore.observability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class KoreMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = KoreMetrics(registry)

    @Test
    fun `agentsActive starts at 0`() {
        metrics.agentsActive.get() shouldBe 0
    }

    @Test
    fun `gauge registered in registry reflects agentsActive value`() {
        val gauge = registry.find("kore.agents.active").gauge()
        gauge shouldNotBe null
        gauge!!.value() shouldBe 0.0
        metrics.agentsActive.incrementAndGet()
        gauge.value() shouldBe 1.0
        metrics.agentsActive.decrementAndGet()
        gauge.value() shouldBe 0.0
    }

    @Test
    fun `agentRunCounter increments kore_agent_runs counter`() {
        metrics.agentRunCounter("my-agent", "success").increment()
        val count =
            registry
                .find("kore.agent.runs")
                .tag("result_type", "success")
                .counter()!!
                .count()
        count shouldBe 1.0
    }

    @Test
    fun `llmCallCounter tags backend correctly`() {
        metrics.llmCallCounter("my-agent", "claude-opus-4-5", "claude").increment()
        val counter =
            registry
                .find("kore.llm.calls")
                .tag("backend", "claude")
                .counter()
        counter shouldNotBe null
    }

    @Test
    fun `tokensUsedCounter with direction in and out are distinct counters`() {
        val inCounter = metrics.tokensUsedCounter("my-agent", "claude-opus-4-5", "in")
        val outCounter = metrics.tokensUsedCounter("my-agent", "claude-opus-4-5", "out")
        (inCounter.id == outCounter.id) shouldBe false
    }

    @Test
    fun `errorCounter registers under kore_errors name with error type tag`() {
        metrics.errorCounter("my-agent", "llm_error").increment()
        val count =
            registry
                .find("kore.errors")
                .tag("error_type", "llm_error")
                .counter()!!
                .count()
        count shouldBe 1.0
    }
}
