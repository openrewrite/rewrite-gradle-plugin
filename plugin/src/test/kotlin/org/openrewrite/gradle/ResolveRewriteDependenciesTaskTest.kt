package org.openrewrite.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ResolveRewriteDependenciesTaskTest : RewritePluginTest {
    // TODO: Extract out into RewritePluginTest? Does JUnit support that?
    @Test
    fun `rewriteResolveDependencies satisfies the configuration cache`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            buildGradle("""
                plugins {
                    id("org.openrewrite.rewrite")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
            """)
        }
        val result = runGradle(projectDir, "rewriteResolveDependencies", "--configuration-cache")
        val rewriteDryRunResult = result.task(":rewriteResolveDependencies")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}