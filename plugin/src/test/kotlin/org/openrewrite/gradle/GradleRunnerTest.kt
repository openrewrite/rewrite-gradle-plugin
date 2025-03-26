package org.openrewrite.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import java.io.File
import java.lang.management.ManagementFactory

val gradleVersion: String? = System.getProperty("org.openrewrite.test.gradleVersion")

interface GradleRunnerTest {

    fun runGradle(testDir: File, vararg args: String): BuildResult {
        return GradleRunner.create()
            .withDebug(ManagementFactory.getRuntimeMXBean().inputArguments.toString().indexOf("-agentlib:jdwp") > 0)
            .withProjectDir(testDir)
            .apply {
                if (gradleVersion != null) {
                    withGradleVersion(gradleVersion)
                }
            }
            .withArguments(*args, "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .build()
    }

    fun lessThanGradle6_1(): Boolean {
        val currentVersion = if (gradleVersion == null) GradleVersion.current() else GradleVersion.version(gradleVersion)
        return currentVersion < GradleVersion.version("6.1")
    }

    fun lessThanGradle6_8(): Boolean {
        val currentVersion = if (gradleVersion == null) GradleVersion.current() else GradleVersion.version(gradleVersion)
        return currentVersion < GradleVersion.version("6.8")
    }

    fun lessThanGradle7_4(): Boolean {
        val currentVersion = if (gradleVersion == null) GradleVersion.current() else GradleVersion.version(gradleVersion)
        return currentVersion < GradleVersion.version("7.4")
    }

    fun isAgp3CompatibleGradleVersion(): Boolean {
        val currentVersion = if (gradleVersion == null) GradleVersion.current() else GradleVersion.version(gradleVersion)
        return System.getenv("ANDROID_HOME") != null &&
                currentVersion >= GradleVersion.version("5.0") &&
                currentVersion < GradleVersion.version("8.0")
    }
}