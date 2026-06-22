/*
 * kore-bom — curated Bill of Materials (KORE-03 / D-04 / D-05).
 *
 * A `java-platform` module that aligns the versions of ONLY the curated,
 * stable v0.1.0 subset of kore modules. Consumers do:
 *
 * ```kotlin
 * dependencies {
 *     implementation(platform("io.github.unityinflow:kore-bom:0.1.0"))
 *     implementation("io.github.unityinflow:kore-spring")  // no version — from the BOM
 * }
 * ```
 *
 * IMPORTANT — this module deliberately does NOT apply `id("kore.publishing")`.
 * That convention applies `java-library` + `from(components["java"])` +
 * sources/javadoc jars, which a `java-platform` has no `java` component for
 * (build error: "SoftwareComponent with name 'java' not found"). So the
 * publishing/signing/POM-metadata blocks below are a minimal hand-rolled copy
 * of the convention, adapted for the BOM's `javaPlatform` component.
 *
 * `group`/`version` are inherited from the root `subprojects { }` block — do
 * NOT re-declare them here.
 */

plugins {
    `java-platform`
    `maven-publish`
    signing
    // Exposes the BOM's outgoing variant to the root nmcp Central aggregation.
    // The nmcp marker is already on the buildSrc classpath (com.gradleup.nmcp:nmcp:1.4.4),
    // so no version is needed here.
    id("com.gradleup.nmcp")
}

dependencies {
    constraints {
        // Curated subset ONLY (D-04). project(...) constraints carry each
        // sibling's version automatically. The three experimental modules
        // (kore-dashboard / kore-kafka / kore-rabbitmq) are deliberately OMITTED.
        api(project(":kore-core"))
        api(project(":kore-llm"))
        api(project(":kore-mcp"))
        api(project(":kore-observability"))
        api(project(":kore-storage"))
        api(project(":kore-skills"))
        api(project(":kore-spring"))
        api(project(":kore-test"))
        api(project(":kore-budget"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
            pom {
                name.set("kore-runtime — BOM")
                description.set(
                    "Curated Bill of Materials aligning the stable v0.1.0 subset of " +
                        "kore-runtime modules (kore-core, kore-llm, kore-mcp, kore-observability, " +
                        "kore-storage, kore-skills, kore-spring, kore-test, kore-budget).",
                )
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
        // Signs the .pom (+ .pom.asc) — a BOM has no jar to sign.
        sign(publishing.publications["maven"])
    } else {
        // Local dev: skip signing so publishToMavenLocal still works. CI sets both.
        logger.lifecycle(
            "[kore-bom] Signing skipped — SIGNING_KEY / SIGNING_PASSWORD env vars not set. " +
                "Release workflow sets them.",
        )
    }
}
