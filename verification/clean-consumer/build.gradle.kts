// Clean-consumer verification build (KORE-06).
//
// "Published" means: a consumer that knows NOTHING about this repo can resolve the curated kore
// coordinates from Maven Central — with module versions aligned by the kore-bom platform import —
// and boot kore's auto-configuration. This build proves exactly that:
//   - Maven Central is the ONLY repository — there is deliberately NO local-repo lookup and NO
//     sibling-module dependency, so a local install or sibling module can never falsely pass
//     the check (T-02-09).
//   - The kore version is supplied via `-PkoreVersion` (never hardcoded) so the harness is
//     reusable; the kore-bom `platform(...)` import aligns the kore-spring / kore-core versions
//     from the BOM, proving the published BOM works for downstream consumers (D-05 / KORE-06).
plugins {
    // This is a STANDALONE build (its own settings root), so it cannot inherit the Kotlin plugin
    // version from the publishable root build's plugins block — the version is pinned here to match
    // the rest of the repo (kore = Kotlin 2.3.0). Resolved from gradlePluginPortal.
    kotlin("jvm") version "2.3.0"
}

repositories {
    // The ONLY repository — the entire point of KORE-06. Do NOT add the local Maven repo: a locally
    // installed jar would let the test pass without the artifact ever reaching Central.
    mavenCentral()
}

// Supplied on the command line via `-PkoreVersion=<x>` (default in gradle.properties so the
// build is configurable without `-P` locally; the script/CI always passes the real version).
val koreVersion: String by project

dependencies {
    // kore-bom platform import aligns the versions of the kore modules below — the version-less
    // kore-spring / kore-core declarations get their version from the BOM, proving the published
    // BOM is usable by a real downstream consumer (D-05).
    testImplementation(platform("io.github.unityinflow:kore-bom:$koreVersion")) // aligns versions
    // No version — resolved from the kore-bom platform. kore-spring brings
    // KoreAutoConfiguration + its AutoConfiguration.imports on the classpath, transitively
    // dragging in kore-core (which defines the EventBus port the test autowires).
    testImplementation("io.github.unityinflow:kore-spring") // no version — from BOM
    testImplementation("io.github.unityinflow:kore-core") // no version — from BOM
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.5") // SB4
    // Gradle 9.0 removed automatic JUnit Platform launcher injection; without this the test
    // executor cannot start ("Failed to load JUnit Platform"). Required on the test runtime
    // classpath under Gradle 9.4.1.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
