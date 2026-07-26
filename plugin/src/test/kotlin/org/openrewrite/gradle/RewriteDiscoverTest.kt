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
import org.junit.jupiter.api.condition.DisabledIf
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.Issue
import org.openrewrite.gradle.fixtures.GradleFixtures
import java.io.File

class RewriteDiscoverTest : RewritePluginTest {

    override fun taskName(): String = "rewriteDiscover"

    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/33")
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
                        url = uri("https://central.sonatype.com/repository/maven-snapshots")
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
        val result = runGradle(projectDir, taskName())
        val rewriteDiscoverResult = result.task(":${taskName()}")!!
        assertThat(rewriteDiscoverResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(result.output).contains("Configured with 2 active recipes and 1 active styles.")
    }

    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/453")
    @Test
    fun `rewriteDiscover picks up recipes rebuilt within the same daemon`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle(GradleFixtures.REWRITE_BUILD_GRADLE + """
                dependencies {
                    rewrite(project(":recipes"))
                }
            """)

            subproject("recipes") {
                buildGradle("""
                    plugins {
                        id("java")
                    }

                    ${GradleFixtures.REPOSITORIES}

                    dependencies {
                        implementation("org.openrewrite:rewrite-core:latest.release")
                    }
                """)

                sourceSet("main") {
                    java(recipe("FirstRecipe"))
                }
            }
        }

        assertThat(runGradle(projectDir, taskName()).output).contains("org.example.FirstRecipe")

        val recipeSources = File(projectDir, "recipes/src/main/java/org/example")
        assertThat(File(recipeSources, "FirstRecipe.java").delete()).isTrue()
        File(recipeSources, "SecondRecipe.java").writeText(recipe("SecondRecipe"))

        assertThat(runGradle(projectDir, taskName()).output).contains("org.example.SecondRecipe")
    }

    //language=java
    private fun recipe(className: String) = """
        package org.example;

        import org.openrewrite.Recipe;

        public class $className extends Recipe {
            @Override
            public String getDisplayName() {
                return "$className";
            }

            @Override
            public String getDescription() {
                return "$className.";
            }
        }
    """.trimIndent()
}
