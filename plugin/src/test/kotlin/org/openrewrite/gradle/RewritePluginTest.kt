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
import org.openrewrite.gradle.fixtures.GradleFixtures
import java.io.File

class RewritePluginTest : GradleRunnerTest {

    @Test
    fun `effective classpath is equal to rewrite plus recipe classpath`(
        @TempDir projectDir: File,
    ) {
        gradleProject(projectDir) {
            buildGradle(
                GradleFixtures.REWRITE_BUILD_GRADLE + """

                dependencies {
                    rewrite(project(":recipes"))
                }

                def plugin = plugins.getPlugin("org.openrewrite.rewrite")
                def extension = project.extensions["rewrite"]
                def configuration = project.configurations["rewrite"]

                def deps = plugin.getResolvedDependencies(project, extension, configuration)
                def effective = new HashSet<>(deps.getEffectiveClasspath().collect { it.getPath() })
                def concatenated = new HashSet<>()
                concatenated.addAll(deps.getFromRewriteOnly().collect { it.getPath() })
                concatenated.addAll(deps.getFromRecipeOnly().collect { it.getPath() })

                if (effective != concatenated) {
                    throw new AssertionError("Effective classpath isn't rewrite + recipe")
                }
                """
            )

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
        val buildResult = runGradle(projectDir, "rewriteDiscover")
        // Make sure our recipe was detected
        assertThat(buildResult.output).contains("org.example.FirstRecipe")

        val taskResult = buildResult.task(":rewriteDiscover")!!
        assertThat(taskResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
