import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.cmroche"

val pluginVersion = providers.gradleProperty("pluginVersion").orElse("0.1.0")
version = pluginVersion.get()

val riderVersion = "2026.1.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    intellijPlatform {
        rider(riderVersion) {
            useInstaller = false
        }
        bundledPlugin("com.intellij.cidr.debugger")
        bundledPlugin("com.jetbrains.rider-cpp")
        bundledModule("intellij.cidr.execution")
        bundledModule("intellij.cidr.projectModel")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks {
    val buildPluginTask = named<BuildPluginTask>("buildPlugin") {
        archiveFileName = pluginVersion.map { "UnrealLauncher-v$it.zip" }
    }

    register("verifyReleaseArtifact") {
        group = "verification"
        description = "Verifies the release archive name and packaged plugin version."
        dependsOn(buildPluginTask)

        val archiveFile = buildPluginTask.flatMap { it.archiveFile }
        inputs.file(archiveFile)
        inputs.property("expectedPluginVersion", pluginVersion)

        doLast {
            val expectedVersion = pluginVersion.get()
            val archive = archiveFile.get().asFile
            val expectedArchiveName = "UnrealLauncher-v$expectedVersion.zip"
            check(archive.name == expectedArchiveName) {
                "Expected release archive '$expectedArchiveName', found '${archive.name}'"
            }

            val pluginXml = ZipFile(archive).use { distributionZip ->
                val expectedPluginJar = "UnrealHelper/lib/UnrealHelper-$expectedVersion.jar"
                val pluginJarEntry = distributionZip.getEntry(expectedPluginJar)
                    ?: error("Release archive does not contain '$expectedPluginJar'")

                ZipInputStream(distributionZip.getInputStream(pluginJarEntry)).use { pluginJar ->
                    generateSequence { pluginJar.nextEntry }
                        .firstOrNull { it.name == "META-INF/plugin.xml" }
                        ?: error("Packaged plugin JAR does not contain META-INF/plugin.xml")
                    pluginJar.readBytes().decodeToString()
                }
            }

            val packagedVersion = Regex("<version>\\s*([^<]+?)\\s*</version>")
                .find(pluginXml)
                ?.groupValues
                ?.get(1)
                ?: error("Packaged META-INF/plugin.xml does not contain a version")
            check(packagedVersion == expectedVersion) {
                "Expected packaged plugin version '$expectedVersion', found '$packagedVersion'"
            }
        }
    }

    test {
        useJUnit()
    }

    val runIdeProjectPath = providers.gradleProperty("unrealHelper.runProject")
        .orElse(
            providers.systemProperty("user.home").map {
                "$it/Projects/UnrealEngine/Samples/Games/Lyra/Lyra.uproject"
            },
        )

    withType<RunIdeTask>().configureEach {
        argumentProviders += CommandLineArgumentProvider {
            listOf(runIdeProjectPath.get())
        }
    }
}
