/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

    fun runFailedGradle(testDir: File, vararg args: String): BuildResult {
        return GradleRunner.create()
            .withDebug(ManagementFactory.getRuntimeMXBean().inputArguments.toString().indexOf("-agentlib:jdwp") > 0)
            .withProjectDir(testDir)
            .apply {
                if (gradleVersion != null) {
                    withGradleVersion(gradleVersion)
                }
            }
            .withArguments(*args)
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()
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
