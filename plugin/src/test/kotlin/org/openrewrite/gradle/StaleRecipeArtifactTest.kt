/*
 * Copyright 2026 the original author or authors.
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
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.Issue
import java.io.File

@Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/458")
class StaleRecipeArtifactTest : GradleRunnerTest {

    @Test
    fun `warns when a dynamic recipe version resolves from Maven Central`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle(recipeDependency("org.openrewrite.recipe:rewrite-testing-frameworks:latest.release"))
        }

        val result = runGradle(projectDir, "rewriteDiscover")

        assertThat(result.output)
            .contains("org.openrewrite.recipe:rewrite-testing-frameworks:latest.release")
            .contains("https://codegenomeproject.org/token")
    }

    @Test
    fun `does not warn when the recipe version is pinned`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle(recipeDependency("org.openrewrite.recipe:rewrite-testing-frameworks:3.42.1"))
        }

        val result = runGradle(projectDir, "rewriteDiscover")

        assertThat(result.output).doesNotContain("https://codegenomeproject.org/token")
    }

    private fun recipeDependency(coordinate: String) = """
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
            rewrite("$coordinate")
        }
    """
}
