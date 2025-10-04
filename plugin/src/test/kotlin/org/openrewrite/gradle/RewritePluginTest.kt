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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.Issue
import org.openrewrite.gradle.condition.EnabledForGradleRange
import java.io.File

interface RewritePluginTest: GradleRunnerTest {

    fun taskName(): String

    // The configuration cache works on Gradle 6.6+, but rewrite-gradle-plugin uses notCompatibleWithConfigurationCache,
    // which is only available on Gradle 7.4+.
    @EnabledForGradleRange(min = "7.4")
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
                       url = uri("https://central.sonatype.com/repository/maven-snapshots")
                    }
                }
                """
            )
        }
        val result = runGradle(projectDir, taskName(), "--configuration-cache")
        val taskResult = result.task(":${taskName()}")!!
        assertThat(taskResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}
