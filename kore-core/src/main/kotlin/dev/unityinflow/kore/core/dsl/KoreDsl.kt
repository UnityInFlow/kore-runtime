package dev.unityinflow.kore.core.dsl

/**
 * DSL marker for all kore builder receivers.
 * Prevents accidental outer-scope method calls inside nested DSL lambdas (Pitfall 10).
 *
 * Every builder class annotated with [@KoreDsl] will not be able to call methods
 * of an enclosing [@KoreDsl]-annotated receiver implicitly, eliminating confusion
 * about which builder scope is being configured.
 */
@DslMarker
annotation class KoreDsl
