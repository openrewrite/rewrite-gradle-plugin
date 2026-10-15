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
import org.junit.jupiter.api.Test
import java.io.File

class DocumentationSyncTest {

    private val extensionSource = File("src/main/java/org/openrewrite/gradle/RewriteExtension.java").readText()

    /**
     * User-facing properties on RewriteExtension that must have Javadoc.
     * Internal/provider properties are excluded.
     */
    private val userFacingFields = listOf(
        "activeRecipes",
        "activeStyles",
        "configFile",
        "checkstyleConfigFile",
        "enableExperimentalGradleBuildScriptParsing",
        "exportDatatables",
        "exclusions",
        "plainTextMasks",
        "sizeThresholdMb",
        "rewriteVersion",
        "logCompilationWarningsAndErrors",
        "failOnInvalidActiveRecipes",
        "failOnDryRunResults",
        "throwOnParseFailures",
        "pomCacheEnabled",
        "pomCacheDirectory",
    )

    private val taskClasses = listOf(
        "src/main/java/org/openrewrite/gradle/RewriteRunTask.java" to "rewriteRun",
        "src/main/java/org/openrewrite/gradle/RewriteDryRunTask.java" to "rewriteDryRun",
        "src/main/java/org/openrewrite/gradle/RewriteDiscoverTask.java" to "rewriteDiscover",
    )

    /**
     * User-facing DSL methods on RewriteExtension that must have Javadoc.
     */
    private val userFacingMethods = listOf(
        "activeRecipe",
        "clearActiveRecipes",
        "activeStyle",
        "clearActiveStyles",
        "exclusion",
        "plainTextMask",
    )

    // Match Javadoc comment followed by optional annotations and a field declaration.
    // The Javadoc must start at the beginning of a line (after optional whitespace).
    private val javadocFieldPattern = Regex("""(?m)^\s*/\*\*\n((?:\s*\*.*\n)*?)\s*\*/\n(?:\s*@\w+(?:\(.*?\))?\s*\n)*\s*(?:private|public|protected)\s+([\w<>,\s]+?)\s+(\w+)\s*([;=].*)""")
    private val javadocMethodPattern = Regex("""(?m)^\s*/\*\*\n((?:\s*\*.*\n)*?)\s*\*/\n\s*public void (\w+)\(([^)]*)\)""")
    private val defaultsToSentence = Regex("""\s*Defaults to ((?:`[^`]*`|[^.`])*)\.""")

    @Test
    fun `all user-facing RewriteExtension fields and methods have Javadoc`() {
        val documented = (javadocFieldPattern.findAll(extensionSource).map { it.groupValues[3] } +
                javadocMethodPattern.findAll(extensionSource).map { it.groupValues[2] }).toSet()

        val undocumented = (userFacingFields + userFacingMethods).filter { it !in documented }
        assertThat(undocumented)
            .withFailMessage("The following RewriteExtension members are missing Javadoc:\n${undocumented.joinToString("\n") { "  - $it" }}")
            .isEmpty()
    }

    @Test
    fun `all task classes have non-empty descriptions`() {
        for ((path, _) in taskClasses) {
            assertThat(taskDescription(path))
                .withFailMessage("$path does not set a task description")
                .isNotBlank()
        }
    }

    @Test
    fun `generated plugin reference is up to date`() {
        val referenceFile = File("../docs/plugin-reference.md")

        // Always write the file so it stays in sync
        referenceFile.parentFile.mkdirs()
        referenceFile.writeText(generatePluginReference())
    }

    private fun generatePluginReference(): String = listOf(
        """
        # OpenRewrite Gradle Plugin Reference

        Automatically eliminate technical debt. Apply OpenRewrite recipes to refactor, migrate, and fix source code across Java, Kotlin, Gradle, XML, YAML, properties, and more.

        New to OpenRewrite? Start with the [quickstart guide](https://docs.openrewrite.org/running-recipes/getting-started), or browse the [recipe catalog](https://docs.openrewrite.org/recipes).
        """.trimIndent(),
        gettingStarted(),
        tasks(),
        configuration(),
        commandLineOverrides(),
        """
        ## Javadoc

        Full API documentation is available in the [Javadoc](apidocs/index.html).
        """.trimIndent(),
    ).joinToString("\n\n", postfix = "\n")

    private fun gettingStarted(): String = listOf(
        """
        ## Getting started

        The plugin is published to the [Code Genome Project](https://codegenomeproject.org/) rather than the Gradle Plugin Portal, since it resolves `org.openrewrite` artifacts that are only available there. Add the repository to `settings.gradle.kts` or `settings.gradle`:
        """.trimIndent(),
        dslTabs(
            """
            pluginManagement {
                repositories {
                    maven {
                        name = "codegenome"
                        url = uri("https://artifacts.codegenomeproject.org/maven")
                        credentials(PasswordCredentials::class)
                    }
                    gradlePluginPortal()
                }
            }
            """.trimIndent(),
            """
            pluginManagement {
                repositories {
                    maven {
                        name = 'codegenome'
                        url = 'https://artifacts.codegenomeproject.org/maven'
                        credentials(PasswordCredentials)
                    }
                    gradlePluginPortal()
                }
            }
            """.trimIndent(),
        ),
        """
        The repository requires authentication. [Sign in to get a download token](https://codegenomeproject.org/token), then add your username and token to `~/.gradle/gradle.properties`:

        ```properties
        codegenomeUsername=you@example.com
        codegenomePassword=<token>
        ```

        Then apply the plugin in `build.gradle.kts` or `build.gradle`, add the recipe modules you need to the `rewrite` configuration, and activate a recipe:
        """.trimIndent(),
        // pages.yml replaces X.Y.Z with the latest release version
        dslTabs(
            """
            plugins {
                id("org.openrewrite.rewrite") version "X.Y.Z"
            }

            repositories {
                mavenCentral()
                maven {
                    name = "codegenome"
                    url = uri("https://artifacts.codegenomeproject.org/maven")
                    credentials(PasswordCredentials::class)
                }
            }

            dependencies {
                rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))
                rewrite("org.openrewrite.recipe:rewrite-static-analysis")
            }

            rewrite {
                activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
            }
            """.trimIndent(),
            """
            plugins {
                id 'org.openrewrite.rewrite' version 'X.Y.Z'
            }

            repositories {
                mavenCentral()
                maven {
                    name = 'codegenome'
                    url = 'https://artifacts.codegenomeproject.org/maven'
                    credentials(PasswordCredentials)
                }
            }

            dependencies {
                rewrite(platform('org.openrewrite.recipe:rewrite-recipe-bom:latest.release'))
                rewrite('org.openrewrite.recipe:rewrite-static-analysis')
            }

            rewrite {
                activeRecipe('org.openrewrite.staticanalysis.CommonStaticAnalysis')
            }
            """.trimIndent(),
        ),
        """
        Preview the changes as a patch in `build/reports/rewrite/rewrite.patch`, then apply them:

        ```bash
        ./gradlew rewriteDryRun
        ./gradlew rewriteRun
        ```
        """.trimIndent(),
    ).joinToString("\n\n")

    private fun tasks(): String = "## Tasks\n\n" + table(
        listOf("Task" to 1, "Description" to 3),
        taskClasses.map { (path, taskName) -> listOf("`$taskName`", taskDescription(path)!!) },
    )

    private fun taskDescription(path: String): String? =
        Regex("""setDescription\("(.+?)"\)""").find(File(path).readText())?.groupValues?.get(1)

    private fun configuration(): String {
        val kotlinIsProperties = Regex("""public boolean is(\w+)\(""").findAll(extensionSource)
            .map { "`is${it.groupValues[1]}`" }
            .toList()
        return listOf(
            """
            ## Configuration

            The plugin is configured via the `rewrite` DSL block in your `build.gradle.kts` or `build.gradle`:
            """.trimIndent(),
            dslTabs(
                """
                rewrite {
                    activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
                    exclusion("src/generated/**")
                    failOnDryRunResults = true
                }
                """.trimIndent(),
                """
                rewrite {
                    activeRecipe('org.openrewrite.staticanalysis.CommonStaticAnalysis')
                    exclusion('src/generated/**')
                    failOnDryRunResults = true
                }
                """.trimIndent(),
            ),
            """
            Applied to the root project of a multi-project build, the plugin covers every subproject, and the root project's `rewrite` DSL and `rewrite` dependency configuration apply to the whole build. Applied to a subproject, it covers only that subproject.

            ### Properties

            In the Kotlin DSL, boolean properties with an `is` getter keep that prefix: ${kotlinIsProperties.joinToString(", ")}.
            """.trimIndent(),
            propertiesTable(),
            defaultPlainTextMasks(),
            "### Methods",
            methodsTable(),
        ).joinToString("\n\n")
    }

    private fun propertiesTable(): String {
        val rows = javadocFieldPattern.findAll(extensionSource)
            .filter { it.groupValues[3] in userFacingFields }
            .map {
                val (rawJavadoc, fieldType, fieldName, rest) = it.destructured
                val type = fieldType.trim().removePrefix("final ")
                val initializer = rest.removePrefix("=").trimEnd(';').trim()
                val javadoc = javadocToMarkdown(rawJavadoc)
                val default = if (fieldName == "plainTextMasks") {
                    "[Built-in list](#default-plain-text-masks)"
                } else {
                    defaultsToSentence.find(javadoc)?.groupValues?.get(1) ?: inferredDefault(type, initializer)
                }
                listOf("`$fieldName`", "`$type`", default, javadoc.replace(defaultsToSentence, "").trim())
            }
            .toList()
        return table(listOf("Property" to 4, "Type" to 2, "Default" to 2, "Description" to 6), rows)
    }

    private fun inferredDefault(type: String, initializer: String): String = when {
        initializer.startsWith("new ArrayList") -> "Empty list"
        initializer.isNotEmpty() -> "`$initializer`"
        type == "boolean" -> "`false`"
        else -> "`null`"
    }

    private fun methodsTable(): String {
        val rows = javadocMethodPattern.findAll(extensionSource)
            .filter { it.groupValues[2] in userFacingMethods }
            .map {
                val (rawJavadoc, name, parameters) = it.destructured
                val parameterTypes = parameters.split(",").filter { p -> p.isNotBlank() }
                    .joinToString(", ") { p -> p.trim().substringBeforeLast(' ') }
                val collectionOverload = if ("public void $name(Collection<String>" in extensionSource) " Also accepts a `Collection<String>`." else ""
                listOf("`$name($parameterTypes)`", javadocToMarkdown(rawJavadoc) + collectionOverload)
            }
            .toList()
        return table(listOf("Method" to 1, "Description" to 3), rows)
    }

    private fun javadocToMarkdown(rawJavadoc: String): String = rawJavadoc
        .lines()
        .joinToString(" ") { it.trimStart().removePrefix("* ").removePrefix("*").trim() }
        .replace(Regex("""\{@code\s+(.*?)\}"""), "`$1`")
        .replace(Regex("""\{@link\s+#?\S+\s+(.*?)\}"""), "$1")
        .replace(Regex("""\{@link\s+(.*?)\}"""), "`$1`")
        .replace(Regex("""\s*<p>\s*"""), " ")
        .replace(Regex("""\s*<pre>.*?</pre>\s*"""), " ")
        .replace("&#47;", "/")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun defaultPlainTextMasks(): String {
        val defaultsList = Regex("""(?s)getPlainTextMasks\(\)\s*\{.*?asList\((.*?)\)\);""").find(extensionSource)!!.groupValues[1]
        val masks = Regex("\"(.*?)\"").findAll(defaultsList).joinToString(" ") { "`${it.groupValues[1]}`" }
        return "<details id=\"default-plain-text-masks\">\n\n<summary>Default plain text masks</summary>\n\n$masks\n\n</details>"
    }

    // Column weights become the separator's dash counts, which pandoc uses as relative column widths
    private fun table(columns: List<Pair<String, Int>>, rows: List<List<String>>): String =
        (listOf(columns.map { it.first }, columns.map { "-".repeat(it.second * 4) }) + rows)
            .joinToString("\n") { cells -> cells.joinToString(" | ", "| ", " |") }

    private fun commandLineOverrides(): String =
        """
        ## Command-line overrides

        System properties take precedence over the `rewrite` DSL, so you can run a recipe once without editing the build. The recipe still has to be on the `rewrite` classpath.

        ```bash
        ./gradlew rewriteRun -Drewrite.activeRecipe=org.openrewrite.staticanalysis.CommonStaticAnalysis
        ```

        | Property | Effect |
        |------------|------------------|
        | `-Drewrite.activeRecipe=<names>` | Replaces `activeRecipes` with a comma-separated list of recipe names. `rewrite.activeRecipes` is accepted too. |
        | `-Drewrite.activeStyle=<names>` | Replaces `activeStyles` with a comma-separated list of style names. `rewrite.activeStyles` is accepted too. |
        | `-Prewrite.throwOnParseFailures` | Enables `throwOnParseFailures`. |
        """.trimIndent()

    // Rendered as a tab per build script language on the site; plain consecutive code blocks elsewhere
    private fun dslTabs(kotlin: String, groovy: String): String = listOf(
        "::: dsl-tabs",
        "```kotlin\n$kotlin\n```",
        "```groovy\n$groovy\n```",
        ":::",
    ).joinToString("\n\n")
}
