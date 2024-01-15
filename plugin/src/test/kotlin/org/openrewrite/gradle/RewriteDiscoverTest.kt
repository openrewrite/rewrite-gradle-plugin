/*
 * Copyright ${year} the original author or authors.
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RewriteDiscoverTest : RewritePluginTest {

    // "https://github.com/openrewrite/rewrite-gradle-plugin/issues/33"
    @Test
    fun `rewriteDiscover prints recipes from external dependencies`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) { 
            buildGradle("""
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }

                dependencies {
                    rewrite("org.openrewrite.recipe:rewrite-testing-frameworks:latest.release")
                }
                
                rewrite {
                     activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
                     activeRecipe("org.openrewrite.java.format.AutoFormat")
                     activeStyle("org.openrewrite.java.SpringFormat")
                }
            """)

        }
        val result = runGradle(projectDir, "rewriteDiscover")
        val rewriteDiscoverResult = result.task(":rewriteDiscover")!!
        assertThat(rewriteDiscoverResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(result.output).contains("Configured with 2 active recipes and 1 active styles.")
    }

    @Test
    fun `rewriteDiscover is compatible with the configuration cache`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle("""
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
            """)
        }
        val result = runGradle(projectDir, "rewriteDiscover", "--configuration-cache")
        val rewriteDryRunResult = result.task(":rewriteDiscover")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}
