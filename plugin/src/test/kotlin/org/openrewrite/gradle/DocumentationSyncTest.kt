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
}
