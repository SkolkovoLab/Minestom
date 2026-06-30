plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Lets the conventions place non-modular jars (ViaVersion/ViaBackwards) on the module path.
    implementation("org.gradlex.extra-java-module-info:org.gradlex.extra-java-module-info.gradle.plugin:1.12")
}
