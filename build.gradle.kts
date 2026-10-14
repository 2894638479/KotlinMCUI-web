import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
}

/** Reads a value from `gradle.properties`, failing the build when it is missing. */
fun gradleProperty(name: String) = providers.gradleProperty(name).get()

val maven_group = gradleProperty("maven_group")
val archives_base_name = gradleProperty("archives_base_name")
val license = gradleProperty("license")
val mod_version = gradleProperty("mod_version")

val toolkit_version = gradleProperty("toolkit_version")
val java_version = gradleProperty("java_version")
val kotlinmcui_version = gradleProperty("kotlinmcui_version")

val slf4j_version = gradleProperty("slf4j_version")
val fastutil_version = gradleProperty("fastutil_version")
val coroutines_version = gradleProperty("coroutines_version")
val serialization_version = gradleProperty("serialization_version")

base.archivesName = archives_base_name
group = maven_group
version = mod_version

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

// The platform supplies every runtime dependency of the core library, so this backend links against
// them exactly the way the core does and never bundles them.
val slf4j = "org.slf4j:slf4j-api:$slf4j_version"
val slf4jSimple = "org.slf4j:slf4j-simple:$slf4j_version"
val fastutil = "it.unimi.dsi:fastutil:$fastutil_version"
val serializationCore = "org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization_version"
val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version"
val coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version"

/**
 * The core library: the sibling checkout's own jar when it has been built, the published one
 * otherwise. A jar file dependency carries nothing with it, which is why every runtime dependency
 * the core names is listed again below for whatever needs to actually run it.
 */
val coreLibrary: Any = file("../kotlinmcui/build/libs/kotlinmcui-$kotlinmcui_version.jar")
    .takeIf { it.exists() }
    ?.let { files(it.path) }
    ?: "com.github.2894638479:KotlinMCUI:v$kotlinmcui_version"

/** What `main()` needs at runtime. Kept out of `runtimeClasspath` so the built jar stays a library. */
val standalone = configurations.create("standalone") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(coreLibrary)
    compileOnly(slf4j)
    compileOnly(fastutil)
    compileOnly(coroutines)
    compileOnly(serializationCore)
    compileOnly(serializationJson)
    // The framework's preview session reads Kotlin reflection to tell which page parameters have
    // defaults; it is never bundled, so the standalone run asks for it explicitly.
    compileOnly(kotlin("reflect"))

    standalone(coreLibrary)
    standalone(slf4j)
    // A logging backend, so a standalone run says what it is doing.
    standalone(slf4jSimple)
    standalone(fastutil)
    standalone(coroutines)
    standalone(serializationCore)
    standalone(serializationJson)
    standalone(kotlin("reflect"))

    // The tests render real pages through the framework, so they need the core library and every
    // dependency it names at runtime - a jar dependency brings no transitive dependency with it.
    testImplementation(kotlin("test"))
    testImplementation(coreLibrary)
    testImplementation(slf4j)
    testImplementation(fastutil)
    testImplementation(coroutines)
    testImplementation(serializationCore)
    testImplementation(serializationJson)
}

kotlin {
    jvmToolchain(toolkit_version.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.valueOf("JVM_$java_version"))
        // Link against the JDK 8 API surface rather than the toolchain's, exactly as the core
        // library does: a JDK 9+ call would compile here and fail on an older runtime.
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.valueOf("VERSION_$java_version")
    targetCompatibility = JavaVersion.valueOf("VERSION_$java_version")
}

val sourcesJar = tasks.named<Jar>("sourcesJar").get()
sourcesJar.from("LICENSE")

tasks.jar {
    from("LICENSE")
}

tasks.test {
    useJUnitPlatform()
    // The recorder's measurements are the point of some of these tests, so their output is not
    // swallowed by the test runner.
    testLogging { showStandardStreams = true }
}

/**
 * Records frames of a page and reports what a web client would have to receive for them.
 *
 * `-Ppage=<name>` picks the page (`color`, `render`, `transform`, `entry`, `synthetic`),
 * `-Pframes=<n>` how many frames to record, and `-Psvg=<file>` writes the last frame as SVG.
 */
tasks.register<JavaExec>("measure") {
    group = "application"
    description = "Records frames of a demo page and reports op counts, wire size and frame reuse."
    mainClass.set("io.github.u2894638479.kotlinmcuiweb.MeasureKt")
    classpath = files(sourceSets["main"].output, sourceSets["main"].runtimeClasspath, standalone)
    workingDir = projectDir
    providers.gradleProperty("page").orNull?.let { args("--page", it) }
    providers.gradleProperty("frames").orNull?.let { args("--frames", it) }
    providers.gradleProperty("svg").orNull?.let { args("--svg", it) }
}
