import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.exceptions.MissingVersionException
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import kotlin.io.path.absolute
import kotlin.io.path.isDirectory

plugins {
    alias(libs.plugins.changelog)
    alias(libs.plugins.gradleIntelliJPlatform)
    alias(libs.plugins.gradleJvmWrapper)
    alias(libs.plugins.kotlinJvm)
    id("java")
}

allprojects {
    repositories {
        mavenCentral()
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

val pluginVersion: String by project
val pluginSdkVersion: String by project
val untilBuildVersion: String by project
val buildConfiguration: String by project

version = pluginVersion

dependencies {
    intellijPlatform {
//        intellijIdeaCommunity(pluginSdkVersion)
        intellijIdeaUltimate(pluginSdkVersion)
        jetbrainsRuntime()
        instrumentationTools()
        testFramework(TestFrameworkType.Platform.Bundled)
    }
    testImplementation(libs.openTest4J)
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


tasks {
    patchPluginXml {
        untilBuild.set(untilBuildVersion)
        val latestChangelog = try {
            changelog.getUnreleased()
        } catch (_: MissingVersionException) {
            changelog.getLatest()
        }
        changeNotes.set(provider {
            changelog.renderItem(
                latestChangelog
                    .withHeader(false)
                    .withEmptySections(false),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        })
    }

    runIde {
        jvmArgs("-Xmx1500m")
    }

    test {
        useTestNG()
        testLogging {
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }
        environment["LOCAL_ENV_RUN"] = "true"
    }
}

val riderModel: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

fun File.writeTextIfChanged(content: String) {
    val bytes = content.toByteArray()

    if (!exists() || !readBytes().contentEquals(bytes)) {
        println("Writing $path")
        parentFile.mkdirs()
        writeBytes(bytes)
    }
}