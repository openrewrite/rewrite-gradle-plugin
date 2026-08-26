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

import org.gradle.util.GradleVersion

/**
 * The repositories builds launched by TestKit resolve `org.openrewrite` artifacts from: the Code Genome
 * Project when `plugin/build.gradle.kts` passed its credentials through as system properties, and Maven
 * Central plus Sonatype snapshots when it could not, as on fork pull requests.
 */
internal object TestKitRepositories {

    private const val CODE_GENOME_URL = "https://artifacts.codegenomeproject.org/maven"
    private const val SONATYPE_SNAPSHOTS_URL = "https://central.sonatype.com/repository/maven-snapshots"

    private val username: String? = System.getProperty("codegenomeUsername")?.ifEmpty { null }
    private val password: String? = System.getProperty("codegenomePassword")?.ifEmpty { null }

    // Repository content filtering arrived in Gradle 5.1, and this suite still tests Gradle 4.10.
    private val supportsContentFiltering: Boolean =
        GradleVersion.version(System.getProperty("org.openrewrite.test.gradleVersion", "8.0")) >=
                GradleVersion.version("5.1")

    //language=groovy
    private val DECLARATIONS: String = if (username == null || password == null) {
        """
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("$SONATYPE_SNAPSHOTS_URL")
        }
        """.trimIndent()
    } else if (supportsContentFiltering) {
        listOf("mavenLocal()", codeGenomeRepository(username, password), "mavenCentral()").joinToString("\n")
    } else {
        // Unscoped, CGP has to go last, or every third-party dependency would be looked up there first.
        listOf("mavenLocal()", "mavenCentral()", codeGenomeRepository(username, password)).joinToString("\n")
    }

    private fun codeGenomeRepository(username: String, password: String): String {
        val lines = mutableListOf(
            "maven {",
            """    url = uri("$CODE_GENOME_URL")""",
            "    credentials {",
            "        username = ${username.asGroovyString()}",
            "        password = ${password.asGroovyString()}",
            "    }"
        )
        if (supportsContentFiltering) {
            lines += listOf(
                "    content {",
                """        includeGroupByRegex("org[.]openrewrite.*")""",
                "    }"
            )
        }
        lines += "}"
        return lines.joinToString("\n")
    }

    /** The declarations without an enclosing `repositories { }`, for a `dependencyResolutionManagement` block. */
    fun declarations(indent: Int): String = DECLARATIONS.indentedForInterpolation(indent)

    fun block(indent: Int): String =
        ("repositories {\n" + DECLARATIONS.prependIndent("    ") + "\n}").indentedForInterpolation(indent)

    // Indents every line but the first, so the result reads correctly interpolated at that indent.
    private fun String.indentedForInterpolation(indent: Int) = replaceIndent(" ".repeat(indent)).trimStart()

    // Single quoted, so that Groovy does not interpolate a `$` in a token.
    private fun String.asGroovyString() = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
