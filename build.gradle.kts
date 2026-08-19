import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.lang.reflect.Modifier

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.jetpack"
version = "1.0.7"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

// The Paper API is compiled for Java 25, so reading it requires a JDK 25 toolchain.
// Output stays on Java 21 so the plugin still loads on servers running the minimum supported JDK.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

// The Paper API is only published for JVM 25, so resolution would otherwise reject it against a
// Java 21 target. It is compileOnly and never shipped, so accepting it here does not affect output.
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

fun resolvedArtifacts() = configurations["compileClasspath"]
    .resolvedConfiguration.resolvedArtifacts

fun paperJar() = resolvedArtifacts()
    .find { it.moduleVersion.id.group == "io.papermc.paper" && it.moduleVersion.id.name == "paper-api" }
    ?.file ?: error("Paper API jar not found")

fun buildClassLoader() = URLClassLoader(
    resolvedArtifacts().map { it.file.toURI().toURL() }.toTypedArray(),
    ClassLoader.getSystemClassLoader(),
)

fun scanEvents(classLoader: ClassLoader, jar: File): List<Pair<String, String>> {
    val eventClass = classLoader.loadClass("org.bukkit.event.Event")
    val found = mutableMapOf<String, MutableList<String>>()
    JarFile(jar).use {
        val entries = it.entries()
        while (entries.hasMoreElements()) {
            val entry: JarEntry = entries.nextElement()
            if (!entry.name.endsWith(".class") || entry.name.contains('$')) continue
            val className = entry.name.removeSuffix(".class").replace('/', '.')
            if (!className.startsWith("org.bukkit.") && !className.startsWith("io.papermc.")) continue
            runCatching {
                val cls = classLoader.loadClass(className)
                if (eventClass.isAssignableFrom(cls)
                    && cls != eventClass
                    && !Modifier.isAbstract(cls.modifiers)
                    && !cls.isInterface
                ) {
                    found.getOrPut(cls.simpleName) { mutableListOf() }.add(className)
                }
            }
        }
    }
    return found.map { (simple, fqns) ->
        simple to (fqns.firstOrNull { it.startsWith("io.papermc.") } ?: fqns.first())
    }.sortedBy { it.first }
}

// Event classes are recorded as names rather than class literals: the catalog is generated from the
// newest Paper API, and servers on older supported versions legitimately lack some of these classes.
// A class literal would make the whole enum fail to initialize there; a name fails only for itself.
tasks.register("generateEventCatalog") {
    val enumFile = file("src/main/kotlin/dev/jetpack/event/JetpackEvent.kt")
    val indexFile = file("generated/events.json")
    inputs.files(paperJar())
    outputs.files(enumFile, indexFile)

    doLast {
        val events = scanEvents(buildClassLoader(), paperJar())

        enumFile.parentFile.mkdirs()
        enumFile.writeText(buildString {
            appendLine("package dev.jetpack.event")
            appendLine()
            appendLine("/* Auto-generated from Paper API. Do not edit manually. */")
            appendLine("enum class JetpackEvent(")
            appendLine("    val className: String,")
            appendLine(") {")
            events.forEachIndexed { i, (simple, fqn) ->
                val terminator = if (i < events.size - 1) "," else ";"
                appendLine("    $simple(\"$fqn\")$terminator")
            }
            appendLine()
            appendLine("    companion object {")
            appendLine("        private val byName = entries.associateBy { it.name }")
            appendLine("        fun resolve(name: String): JetpackEvent? = byName[name]")
            appendLine("    }")
            appendLine("}")
        })

        indexFile.parentFile.mkdirs()
        indexFile.writeText(events.joinToString(",\n  ", "[\n  ", "\n]\n") { "\"${it.first}\"" })
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateEventCatalog")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("Jetpack")
    archiveFileName.set("Jetpack.jar")
    archiveClassifier.set("")

    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
