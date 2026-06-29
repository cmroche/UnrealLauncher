import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.cmroche"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    intellijPlatform {
        rider("2026.1.2") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.cidr.debugger")
        bundledPlugin("com.jetbrains.rider-cpp")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.cidr.execution")
        bundledModule("intellij.cidr.projectModel")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
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
