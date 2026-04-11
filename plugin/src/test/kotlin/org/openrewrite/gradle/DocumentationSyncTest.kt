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
    )

    @Test
    fun `all user-facing RewriteExtension fields have Javadoc`() {
        val fieldPattern = Regex("""(?s)/\*\*.*?\*/\s*(?:@\w+\s+)*(?:private|public|protected)\s+.*?\b(\w+)\s*[;=]""")
        val documentedFields = fieldPattern.findAll(extensionSource)
            .map { it.groupValues[1] }
            .toSet()

        val undocumented = userFacingFields.filter { it !in documentedFields }
        assertThat(undocumented)
            .withFailMessage("The following RewriteExtension fields are missing Javadoc:\n${undocumented.joinToString("\n") { "  - $it" }}")
            .isEmpty()
    }

    @Test
    fun `all task classes have non-empty descriptions`() {
        val taskFiles = listOf(
            "src/main/java/org/openrewrite/gradle/RewriteRunTask.java",
            "src/main/java/org/openrewrite/gradle/RewriteDryRunTask.java",
            "src/main/java/org/openrewrite/gradle/RewriteDiscoverTask.java",
        )
        for (path in taskFiles) {
            val source = File(path).readText()
            assertThat(source)
                .withFailMessage("$path does not call setDescription()")
                .contains("setDescription(")
            val desc = Regex("""setDescription\("(.+?)"\)""").find(source)?.groupValues?.get(1)
            assertThat(desc)
                .withFailMessage("$path has an empty task description")
                .isNotBlank()
        }
    }

    private val taskClasses = listOf(
        "src/main/java/org/openrewrite/gradle/RewriteRunTask.java" to "rewriteRun",
        "src/main/java/org/openrewrite/gradle/RewriteDryRunTask.java" to "rewriteDryRun",
        "src/main/java/org/openrewrite/gradle/RewriteDiscoverTask.java" to "rewriteDiscover",
    )

    @Test
    fun `generated plugin reference is up to date`() {
        val generated = generatePluginReference()
        val referenceFile = File("../docs/plugin-reference.md")

        // Always write the file so it stays in sync
        referenceFile.parentFile.mkdirs()
        referenceFile.writeText(generated)
    }

    private fun generatePluginReference(): String {
        val sb = StringBuilder()
        sb.appendLine("# OpenRewrite Gradle Plugin Reference")
        sb.appendLine()
        sb.appendLine("Automatically eliminate technical debt. Apply OpenRewrite recipes to refactor, migrate, and fix source code across Java, Kotlin, Gradle, XML, YAML, properties, and more.")
        sb.appendLine()
        sb.appendLine("## Tasks")
        sb.appendLine()

        for ((path, taskName) in taskClasses) {
            val source = File(path).readText()
            val description = Regex("""setDescription\("(.+?)"\)""").find(source)!!.groupValues[1]
            sb.appendLine("### `$taskName`")
            sb.appendLine()
            sb.appendLine(description)
            sb.appendLine()
        }

        sb.appendLine("## Configuration")
        sb.appendLine()
        sb.appendLine("The plugin is configured via the `rewrite` DSL block in your `build.gradle` or `build.gradle.kts`:")
        sb.appendLine()
        sb.appendLine("```groovy")
        sb.appendLine("rewrite {")
        sb.appendLine("    activeRecipe(\"org.openrewrite.java.format.AutoFormat\")")
        sb.appendLine("    exclusion(\"src/generated/**\")")
        sb.appendLine("}")
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("### Properties")
        sb.appendLine()
        sb.appendLine("| Property | Type | Default | Description |")
        sb.appendLine("|----------|------|---------|-------------|")

        // Match Javadoc comment followed by optional annotations and a field declaration.
        // The Javadoc must start at the beginning of a line (after optional whitespace).
        val javadocFieldPattern = Regex("""(?m)^\s*/\*\*\n((?:\s*\*.*\n)*?)\s*\*/\n(?:\s*@\w+(?:\(.*?\))?\s*\n)*\s*(?:private|public|protected)\s+([\w<>,\s]+?)\s+(\w+)\s*([;=].*)""")

        for (match in javadocFieldPattern.findAll(extensionSource)) {
            val rawJavadoc = match.groupValues[1]
            val fieldType = match.groupValues[2].trim()
            val fieldName = match.groupValues[3]
            val rest = match.groupValues[4]
            val initializer = if (rest.startsWith("=")) {
                rest.drop(1).trimEnd(';').trim()
            } else {
                ""
            }

            if (fieldName !in userFacingFields) continue

            // Clean up Javadoc: strip * prefixes, {@code ...} -> `...`, collapse whitespace
            val javadoc = rawJavadoc
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

            // Determine display type
            val displayType = when {
                fieldType.contains("List<String>") -> "`List<String>`"
                fieldType.contains("boolean") -> "`boolean`"
                fieldType.contains("int") -> "`int`"
                fieldType.contains("String") -> "`String`"
                fieldType.contains("File") -> "`File`"
                else -> "`$fieldType`"
            }

            // Determine default value from field initializer, with overrides for constructor-set fields
            val default = when (fieldName) {
                "configFile" -> "`rewrite.yml`"
                else -> when {
                    initializer.startsWith("new ArrayList") -> "Empty list"
                    initializer == "true" -> "`true`"
                    initializer == "false" -> "`false`"
                    initializer.matches(Regex("\\d+")) -> "`$initializer`"
                    fieldType.contains("boolean") && initializer.isEmpty() -> "`false`"
                    fieldType.contains("String") && initializer.isEmpty() -> "`null`"
                    fieldType.contains("File") && initializer.isEmpty() -> "`null`"
                    else -> ""
                }
            }

            sb.appendLine("| `$fieldName` | $displayType | $default | $javadoc |")
        }

        sb.appendLine()
        sb.appendLine("## Javadoc")
        sb.appendLine()
        sb.appendLine("Full API documentation is available in the [Javadoc](apidocs/index.html).")
        sb.appendLine()

        return sb.toString()
    }
}
