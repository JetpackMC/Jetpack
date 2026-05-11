import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.lang.reflect.Modifier

plugins {
    kotlin("jvm") version "2.1.0"
}

group = "dev.jetpack"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
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

tasks.register("generateEventEnum") {
    val outputFile = file("src/main/kotlin/dev/jetpack/event/JetpackEvent.kt")
    inputs.files(paperJar())
    outputs.file(outputFile)

    doLast {
        val classLoader = buildClassLoader()
        val events = scanEvents(classLoader, paperJar())

        outputFile.parentFile.mkdirs()
        outputFile.writeText(buildString {
            appendLine("package dev.jetpack.event")
            appendLine("import org.bukkit.event.Event")
            appendLine()
            appendLine("/* Auto-generated from Paper API. Do not edit manually. */")
            appendLine("enum class JetpackEvent(")
            appendLine("    val eventClass: Class<out Event>,")
            appendLine(") {")
            events.forEachIndexed { i, (simple, fqn) ->
                val comma = if (i < events.size - 1) "," else ";"
                appendLine("    $simple($fqn::class.java)$comma")
            }
            appendLine()
            appendLine("    companion object {")
            appendLine("        private val byName = entries.associateBy { it.name }")
            appendLine("        fun resolve(name: String): JetpackEvent? = byName[name]")
            appendLine("    }")
            appendLine("}")
        })
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateEventEnum")
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
