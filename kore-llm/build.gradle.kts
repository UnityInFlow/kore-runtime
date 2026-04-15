plugins {
    alias(libs.plugins.kotlin.jvm)
    id("kore.publishing")
}

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.coroutines.core)
    implementation(libs.anthropic.java)
    implementation(libs.anthropic.java.okhttp)
    implementation(libs.openai.java)
    implementation(libs.langchain4j.ollama)
    implementation(libs.langchain4j.gemini)

    testImplementation(project(":kore-test"))
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — LLM backends")
                description.set(
                    "Claude, GPT, Ollama, and Gemini LLMBackend adapters for kore-runtime.",
                )
            }
        }
    }
}
