/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
 */
package org.openrewrite.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIf
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.Issue
import java.io.File
import java.lang.management.ManagementFactory

val gradleVersion: String? = System.getProperty("org.openrewrite.test.gradleVersion")

interface RewritePluginTest {

    fun taskName(): String

    fun runGradle(testDir: File, vararg args: String): BuildResult {
        return GradleRunner.create()
            .withDebug(ManagementFactory.getRuntimeMXBean().inputArguments.toString().indexOf("-agentlib:jdwp") > 0)
            .withProjectDir(testDir)
            .apply {
                if (gradleVersion != null) {
                    withGradleVersion(gradleVersion)
                }
            }
            .withArguments(*args, "--stacktrace")
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

    // The configuration cache works on Gradle 6.6+, but rewrite-gradle-plugin uses notCompatibleWithConfigurationCache,
    // which is only available on Gradle 7.4+.
    @DisabledIf("lessThanGradle7_4")
    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/227")
    @Test
    fun `task is compatible with the configuration cache`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle(
                """
                plugins {
                    id("org.openrewrite.rewrite")
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
                """
            )
        }
        val result = runGradle(projectDir, taskName(), "--configuration-cache")
        val taskResult = result.task(":${taskName()}")!!
        assertThat(taskResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    fun isAgp3CompatibleGradleVersion(): Boolean {
        val currentVersion = if (gradleVersion == null) GradleVersion.current() else GradleVersion.version(gradleVersion)
        return System.getenv("ANDROID_HOME") != null &&
                currentVersion >= GradleVersion.version("5.0") &&
                currentVersion < GradleVersion.version("8.0")
    }
}
