plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

    group = "dev.unityinflow"
    version = "0.0.1-SNAPSHOT"
}
