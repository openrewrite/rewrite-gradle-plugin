package org.openrewrite.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.gradle.fixtures.GradleFixtures.Companion.REWRITE_BUILD_GRADLE
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
    fun `can use default source set directories`() {
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

        assertTaskOutcome(result)
        assertPatchFileContents(DEFAULT_SRC_DIR)
    }

    @Test
    fun `can use only custom Java source set directory`() {
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

            createJavaInterfaceFile(CUSTOM_SRC_DIR)
            createJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(DEFAULT_RESOURCES_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertTaskOutcome(result)
        assertPatchFileContents(CUSTOM_SRC_DIR)
    }

    @Test
    fun `can use non-overlapping custom source set directories`() {
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

            createJavaInterfaceFile(CUSTOM_SRC_DIR)
            createJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_RESOURCES_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertTaskOutcome(result)
        assertPatchFileContents(CUSTOM_SRC_DIR)
    }

    @Test
    fun `can use overlapping custom source set directories`() {
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

            createJavaInterfaceFile(CUSTOM_SRC_DIR)
            createJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_SRC_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertTaskOutcome(result)
        assertPatchFileContents(CUSTOM_SRC_DIR)
    }

    @Test
    fun `can add custom source set directories to default directories`() {
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

            createJavaInterfaceFile(CUSTOM_SRC_DIR)
            createJavaClassFile(CUSTOM_SRC_DIR)
            createYamlFile(CUSTOM_SRC_DIR)
        }

        val result = runGradle(projectDir, TASK_NAME)

        assertTaskOutcome(result)
        assertPatchFileContents(CUSTOM_SRC_DIR)
    }

    private fun createJavaInterfaceFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "HelloWorld.java", HELLO_WORLD_JAVA_INTERFACE)
    }

    private fun createJavaClassFile(sourceDir: String) {
        createJavaFile(projectDir, sourceDir, "HelloWorldImpl.java", HELLO_WORLD_JAVA_CLASS)
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

    private fun assertTaskOutcome(result: BuildResult) {
        val rewriteDryRunResult = result.task(":${TASK_NAME}")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun assertPatchFileContents(sourceDir: String) {
        val patchFile = File(projectDir, "build/reports/rewrite/rewrite.patch")
        assertThat(patchFile.exists()).isTrue
        assertThat(patchFile.readText()).contains(
            "rename from ${sourceDir}/org/openrewrite/before/HelloWorld.java",
            "rename to ${sourceDir}/org/openrewrite/after/HelloWorld.java",
            "rename from ${sourceDir}/org/openrewrite/before/HelloWorldImpl.java",
            "rename to ${sourceDir}/org/openrewrite/after/HelloWorldImpl.java"
        )
    }
}