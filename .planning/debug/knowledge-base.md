# GSD Debug Knowledge Base

Resolved debug sessions. Used by `gsd-debugger` to surface known-pattern hypotheses at the start of new investigations.

---

## kore-observability-test-noclassdeffound -- kotlinx-serialization-core missing from downstream test classpath
- **Date:** 2026-04-16
- **Error patterns:** NoClassDefFoundError, KSerializer, ExceptionInInitializerError, ClassNotFoundException, clinit, AgentResult, AgentEvent, compileOnly, serialization
- **Root cause:** kore-core declares kotlinx-serialization-core as compileOnly. The @Serializable annotation generates <clinit> code referencing KSerializer. Downstream modules that instantiate @Serializable classes (via MockK or directly) at test time fail with ClassNotFoundException because compileOnly deps don't propagate.
- **Fix:** Added `testImplementation(libs.serialization.core)` to kore-observability/build.gradle.kts.
- **Files changed:** kore-observability/build.gradle.kts
---
