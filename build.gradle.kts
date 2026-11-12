plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    val buildRoot = project.findProperty("buildRoot") as String? ?: "/tmp/build"
    layout.buildDirectory.set(file("$buildRoot/${project.name}"))
}
