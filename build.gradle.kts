plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

val quillVersion: String = providers.gradleProperty("quill.version").get()

allprojects {
    group = "dev.starfect.quill"
    version = quillVersion
}
