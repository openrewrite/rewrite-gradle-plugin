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
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.gradle.fixtures.GradleFixtures.Companion.REWRITE_BUILD_GRADLE
import org.openrewrite.gradle.fixtures.JavaFixtures.Companion.GOODBYE_WORLD_JAVA_CLASS
import org.openrewrite.gradle.fixtures.JavaFixtures.Companion.GOODBYE_WORLD_JAVA_INTERFACE
import org.openrewrite.gradle.fixtures.JavaFixtures.Companion.HELLO_WORLD_JAVA_CLASS
import org.openrewrite.gradle.fixtures.JavaFixtures.Companion.HELLO_WORLD_JAVA_INTERFACE
import java.io.File

class RewriteDryRunSourceSetTest : GradleRunnerTest {
    @TempDir
    lateinit var projectDir: File

    companion object {
        const val TASK_NAME = "rewriteDryRun"
        const val DEFAULT_SRC_DIR = "src/main/java"
        const val DEFAULT_RESOURCES_DIR = "src/main/resources"
        const val CUSTOM_SRC_DIR = "src"
        const val CUSTOM_RESOURCES_DIR = "resources"

        //language=yaml
        const val REWRITE_YAML =
            """
            type: specs.openrewrite.org/v1beta/recipe
            name: org.openrewrite.gradle.ChangePackage
            description: Test.
            recipeList:
              - org.openrewrite.java.ChangePackage:
                  oldPackageName: org.openrewrite.before
                  newPackageName: org.openrewrite.after
            """

        //language=groovy
        const val REWRITE_PLUGIN_ACTIVE_RECIPE = """
            rewrite {
                activeRecipe("org.openrewrite.gradle.ChangePackage", "org.openrewrite.java.format.AutoFormat")
            }
        """
    }

    @Test
    fun `can run a recipe for default source set directories`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                REWRITE_PLUGIN_ACTIVE_RECIPE
            )

            sourceSet("main") {
                java(HELLO_WORLD_JAVA_INTERFACE)
                java(HELLO_WORLD_JAVA_CLASS)
                yamlFile(
                    "foo.yml", "foo: bar"
                )
            }
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, DEFAULT_SRC_DIR)
    }

    @Test
    fun `can run a recipe for custom Java source set directory with default directories`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                        REWRITE_PLUGIN_ACTIVE_RECIPE +
                        //language=groovy
                        """
                sourceSets {
                    main {
                        java {
                            srcDirs = ["src"]
                        }
                    }
                }
            """
            )

            createHelloWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createHelloWorldJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(DEFAULT_RESOURCES_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
    }

    @Test
    fun `can run a recipe for non-overlapping custom source set directories`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                REWRITE_PLUGIN_ACTIVE_RECIPE +
                //language=groovy
                """
                sourceSets {
                    main {
                        java {
                            srcDirs = ["src"]
                        }
                        resources {
                            srcDirs = ["resources"]
                        }
                    }
                }
            """
            )

            createHelloWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createHelloWorldJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_RESOURCES_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
    }

    @Test
    fun `can run a recipe for overlapping custom source set directories`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                REWRITE_PLUGIN_ACTIVE_RECIPE +
                //language=groovy
                """
                sourceSets {
                    main {
                        java {
                            srcDirs = ["src"]
                        }
                        resources {
                            srcDirs = ["src"]
                            excludes = ["**/*.java"]
                        }
                    }
                }
            """
            )

            createHelloWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createHelloWorldJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_SRC_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
    }

    @Test
    fun `can run a recipe for custom source set directories added to default directories`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                REWRITE_PLUGIN_ACTIVE_RECIPE +
                //language=groovy
                """
                sourceSets {
                    main {
                        java {
                            srcDir "src"
                        }
                        resources {
                            srcDir "src"
                            excludes = ["**/*.java"]
                        }
                    }
                }
            """
            )

            createHelloWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createHelloWorldJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_SRC_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
    }

    @Test
    fun `can run a recipe for overlapping source sets`() {
        gradleProject(projectDir) {
            rewriteYaml(REWRITE_YAML)
            buildGradle(
                REWRITE_BUILD_GRADLE +
                REWRITE_PLUGIN_ACTIVE_RECIPE +
                //language=groovy
                """
                sourceSets {
                    main {
                        java {
                            srcDirs = ["src"]
                        }
                    }
                    other {
                        java {
                            srcDirs = ["src"]
                        }
                    }
                }
            """
            )

            createHelloWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createHelloWorldJavaClassFile(CUSTOM_SRC_DIR)
            createGoodbyeWorldJavaInterfaceFile(CUSTOM_SRC_DIR)
            createGoodbyeWorldJavaClassFile(CUSTOM_SRC_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertDryRunTaskOutcome(result)
        val compileOtherJavaResult = result.task(":compileOtherJava")!!
        assertThat(compileOtherJavaResult.outcome).isEqualTo(TaskOutcome.SKIPPED)
        val patchFile = assertPatchFile()
        assertHelloWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
        assertGoodbyeWorldInPatchFileContents(patchFile, CUSTOM_SRC_DIR)
    }

    private fun createHelloWorldJavaInterfaceFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "HelloWorld.java", HELLO_WORLD_JAVA_INTERFACE)
    }

    private fun createHelloWorldJavaClassFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "HelloWorldImpl.java", HELLO_WORLD_JAVA_CLASS)
    }

    private fun createGoodbyeWorldJavaInterfaceFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "GoodbyeWorld.java", GOODBYE_WORLD_JAVA_INTERFACE)
    }

    private fun createGoodbyeWorldJavaClassFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "GoodbyeWorldImpl.java", GOODBYE_WORLD_JAVA_CLASS)
    }

    private fun createJavaFile(projectDir: File, sourceDir: String, fileName: String, content: String) {
        val dir = File(projectDir, sourceDir)
        dir.mkdirs()
        val packageDir = File(dir, "org/openrewrite/before")
        packageDir.mkdirs()
        File(packageDir, fileName).writeText(content)
    }

    private fun createYamlFile(sourceDir: String) {
        val dir = File(projectDir, sourceDir)
        dir.mkdirs()
        File(dir, "foo.yml").writeText("foo: test")
    }

    private fun assertDryRunTaskOutcome(result: BuildResult) {
        val rewriteDryRunResult = result.task(":${TASK_NAME}")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun assertPatchFile(): File {
        val patchFile = File(projectDir, "build/reports/rewrite/rewrite.patch")
        assertThat(patchFile.exists()).isTrue
        return patchFile
    }

    private fun assertHelloWorldInPatchFileContents(patchFile: File, javaSourceDir: String) {
        assertThat(patchFile.readText()).contains(
            "rename from ${javaSourceDir}/org/openrewrite/before/HelloWorld.java",
            "rename to ${javaSourceDir}/org/openrewrite/after/HelloWorld.java",
            "rename from ${javaSourceDir}/org/openrewrite/before/HelloWorldImpl.java",
            "rename to ${javaSourceDir}/org/openrewrite/after/HelloWorldImpl.java"
        )
    }

    private fun assertGoodbyeWorldInPatchFileContents(patchFile: File, javaSourceDir: String) {
        assertThat(patchFile.readText()).contains(
            "rename from ${javaSourceDir}/org/openrewrite/before/GoodbyeWorld.java",
            "rename to ${javaSourceDir}/org/openrewrite/after/GoodbyeWorld.java",
            "rename from ${javaSourceDir}/org/openrewrite/before/GoodbyeWorldImpl.java",
            "rename to ${javaSourceDir}/org/openrewrite/after/GoodbyeWorldImpl.java"
        )
    }
}