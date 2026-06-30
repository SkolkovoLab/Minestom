plugins {
    java
    application
    alias(libs.plugins.shadow)
    id("org.gradlex.extra-java-module-info")
}

val javaVersion = System.getenv("JAVA_VERSION") ?: "25"

group = "net.minestom"

extraJavaModuleInfo {
    // Lets the conventions place non-modular jars (ViaVersion/ViaBackwards) on the module path.
    failOnMissingModuleInfo = false
    automaticModule("com.viaversion:viaversion", "viaversion")
    automaticModule("com.viaversion:viabackwards", "viabackwards")
}

repositories {
    val dataVersion = libs.minestomData.get().version ?: ""
    if (dataVersion.endsWith("-dev"))
        mavenLocal()
    val adventureVersion = libs.adventure.api.get().version ?: ""
    if (adventureVersion.endsWith("-SNAPSHOT"))
        maven(url = "https://central.sonatype.com/repository/maven-snapshots/")

    mavenCentral()
    maven("https://repo.viaversion.com")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    modularity.inferModulePath = true
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    mergeServiceFiles()
}
