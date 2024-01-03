/*
 * Copyright ${year} the original author or authors.
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
import java.io.File

class RewriteResolveDependenciesTest : RewritePluginTest {
    @Test
    fun `Specifying a rewriteVersion does not cause build failures`(
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
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
                
                rewrite {
                    rewriteVersion = "8.8.0"
                }
            """)
        }

        val result = runGradle(projectDir, "rewriteResolveDependencies")
        val rewriteDryRunResult = result.task(":rewriteResolveDependencies")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

}
