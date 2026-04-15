/*
 * Convention plugin applied by every publishable kore module.
 *
 * Produces signed artifacts suitable for the Sonatype Central Portal
 * (D-05 / D-07):
 *   - main jar
 *   - sources jar  (required by Sonatype)
 *   - javadoc jar  (required by Sonatype; empty is acceptable — Pitfall 4)
 *   - pom with full metadata (name, description, url, licenses,
 *     developers, scm, issueManagement)
 *   - .asc signature for every artifact above (Pitfall 5 defense:
 *     sign the PUBLICATION, not individual tasks)
 *
 * Each module that applies this plugin must declare its own `name` and
 * `description` via:
 *
 * ```kotlin
 * plugins { id("kore.publishing") }
 * publishing {
 *     publications {
 *         named<MavenPublication>("maven") {
 *             pom {
 *                 name.set("kore-runtime — My Module")
 *                 description.set("What this module does in one sentence.")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Signing reads from `SIGNING_KEY` / `SIGNING_PASSWORD` env vars (D-10).
 * Local dev without those env vars still produces unsigned artifacts —
 * useful for `publishToMavenLocal` during development. CI release workflow
 * (plan 04-06) sets both.
 *
 * Pitfall 11: Gradle configuration cache tracks
 * `providers.environmentVariable(...)` values and may use a stale copy
 * when env vars change between runs. Local publishes and the release CI
 * workflow both pass `--no-configuration-cache` to force re-read of the
 * signing env vars on every run.
 */

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

// `java-library` is applied here (not relying on kotlin-jvm from the consuming
// module) so the `java { }` extension and `components["java"]` are visible to
// this precompiled script plugin at compile time. Consuming modules still apply
// their `kotlin("jvm")` plugin; the two plugins coexist (kotlin-jvm layers on
// top of java-base), and the kotlin classes are automatically included in the
// java component.

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                // Module-specific name and description are OVERRIDDEN per-module.
                // These defaults ensure the POM is syntactically valid if a module
                // forgets to override — but the convention here is to always override.
                name.set(provider { project.name })
                description.set(provider { "kore-runtime module: ${project.name}" })
                url.set("https://github.com/UnityInFlow/kore")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("jhermann")
                        name.set("Jiří Hermann")
                        email.set("jiri@unityinflow.dev")
                        organization.set("UnityInFlow")
                        organizationUrl.set("https://github.com/UnityInFlow")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/UnityInFlow/kore.git")
                    developerConnection.set("scm:git:ssh://git@github.com/UnityInFlow/kore.git")
                    url.set("https://github.com/UnityInFlow/kore")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/UnityInFlow/kore/issues")
                }
            }
        }
    }
}

signing {
    val signingKey: String? = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword: String? = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        // CRITICAL — Pitfall 5: sign the publication, not individual tasks.
        // This generates .asc files for the jar, sources jar, javadoc jar, AND the pom.
        sign(publishing.publications["maven"])
    } else {
        // Local dev: if either env var is missing, skip signing so publishToMavenLocal
        // still works for development. CI sets both.
        logger.lifecycle(
            "[kore.publishing] Signing skipped for ${project.name} — " +
                "SIGNING_KEY / SIGNING_PASSWORD env vars not set. Release workflow sets them.",
        )
    }
}
