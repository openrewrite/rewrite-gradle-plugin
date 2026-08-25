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
 * The repositories from which builds launched by TestKit resolve the `org.openrewrite` artifacts
 * that `versions.properties` pins.
 *
 * When the Code Genome Project credentials reach the test JVM as system properties, which
 * `plugin/build.gradle.kts` arranges from the `codegenomeUsername` and `codegenomePassword` Gradle
 * properties, those builds resolve from CGP, the same place the main build resolves from. Without
 * the credentials, as on fork pull requests, they fall back to Maven Central plus the Sonatype
 * snapshots repository.
 *
 * `mavenLocal()` stays first either way, so a locally published `rewrite` build still wins.
 */
internal object TestKitRepositories {

    private const val CODE_GENOME_URL = "https://artifacts.codegenomeproject.org/maven"
    private const val SONATYPE_SNAPSHOTS_URL = "https://central.sonatype.com/repository/maven-snapshots"

    private val username: String? = System.getProperty("codegenomeUsername")?.ifEmpty { null }
    private val password: String? = System.getProperty("codegenomePassword")?.ifEmpty { null }

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
    } else {
        // CGP goes ahead of Maven Central, so that Central is never asked for org.openrewrite artifacts.
        listOf("mavenLocal()", codeGenomeRepository(username, password), "mavenCentral()").joinToString("\n")
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
            // Everything else keeps coming from Maven Central; the regex avoids escapes Groovy rejects.
            lines += listOf(
                "    content {",
                """        includeGroupByRegex("org[.]openrewrite.*")""",
                "    }"
            )
        }
        lines += "}"
        return lines.joinToString("\n")
    }

    /**
     * The repository declarations without an enclosing `repositories { }`, so that they can be dropped
     * into a `dependencyResolutionManagement` block alongside other repositories.
     */
    fun declarations(indent: Int): String = DECLARATIONS.indentedForInterpolation(indent)

    /**
     * The declarations wrapped in a `repositories { }` block.
     */
    fun block(indent: Int): String =
        ("repositories {\n" + DECLARATIONS.prependIndent("    ") + "\n}").indentedForInterpolation(indent)

    /**
     * Indents every line but the first by [indent] spaces, so that the result reads correctly when
     * interpolated into a build script whose lines are indented that far.
     */
    private fun String.indentedForInterpolation(indent: Int) = replaceIndent(" ".repeat(indent)).trimStart()

    /** A single quoted Groovy string, which unlike a double quoted one does not interpolate a `$` in a token. */
    private fun String.asGroovyString() = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
