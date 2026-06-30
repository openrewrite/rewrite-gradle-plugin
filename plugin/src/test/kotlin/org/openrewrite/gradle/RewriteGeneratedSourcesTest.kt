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
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies that the plugin parses annotation-processor generated Java sources (which live under the build
 * directory and are normally skipped) so that recipes can read and act on them. MapStruct is used as a
 * representative annotation processor: a {@code @Mapper} interface causes a {@code *MapperImpl} class to be
 * generated under {@code build/generated/sources/annotationProcessor/java/main} during {@code compileJava}.
 */
class RewriteGeneratedSourcesTest : GradleRunnerTest {

    @TempDir
    lateinit var projectDir: File

    //language=groovy
    private val buildScript = """
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
            implementation("org.mapstruct:mapstruct:1.5.5.Final")
            annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
        }

        rewrite {
            activeRecipe("org.openrewrite.gradle.RenameBeforePackage")
        }
    """

    //language=yaml
    private val recipeYaml = """
        type: specs.openrewrite.org/v1beta/recipe
        name: org.openrewrite.gradle.RenameBeforePackage
        description: Renames the before package, which also touches the generated mapper implementation.
        recipeList:
          - org.openrewrite.java.ChangePackage:
              oldPackageName: org.openrewrite.before
              newPackageName: org.openrewrite.after
    """

    private fun mapStructProject() = gradleProject(projectDir) {
        rewriteYaml(recipeYaml)
        buildGradle(buildScript)
        sourceSet("main") {
            java(
                """
                package org.openrewrite.before;

                public class Car {
                    private String name;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """
            )
            java(
                """
                package org.openrewrite.before;

                public class CarDto {
                    private String name;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """
            )
            java(
                """
                package org.openrewrite.before;

                import org.mapstruct.Mapper;

                @Mapper
                public interface CarMapper {
                    CarDto toDto(Car car);
                }
                """
            )
        }
    }

    @Test
    fun `rewriteDryRun reads generated sources so recipes can act on the generated mapper impl`() {
        mapStructProject()

        val result = runGradle(projectDir, "rewriteDryRun")
        assertThat(result.task(":rewriteDryRun")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)

        // The annotation processor must have produced the mapper implementation under the build directory.
        val generatedImpl = File(
            projectDir,
            "build/generated/sources/annotationProcessor/java/main/org/openrewrite/before/CarMapperImpl.java"
        )
        assertThat(generatedImpl)
            .describedAs("MapStruct should generate the mapper implementation during compileJava")
            .exists()

        // The dry-run patch should include a change to the generated mapper implementation, proving the
        // generated source was parsed and offered to the recipe (it would be absent if generated sources
        // were skipped as they normally are).
        val patch = File(projectDir, "build/reports/rewrite/rewrite.patch")
        assertThat(patch).exists()
        assertThat(patch.readText())
            .describedAs("the generated CarMapperImpl should appear in the rewrite patch")
            .contains("CarMapperImpl")
    }

    @Test
    fun `rewriteRun rewrites generated sources alongside hand-written sources`() {
        mapStructProject()

        val result = runGradle(projectDir, "rewriteRun")
        assertThat(result.task(":rewriteRun")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)

        // Hand-written sources are moved to the new package as usual.
        assertThat(File(projectDir, "src/main/java/org/openrewrite/after/CarMapper.java")).exists()

        // The generated mapper implementation was parsed and the recipe applied to it, so its package
        // declaration now references the renamed package.
        val rewrittenGeneratedImpl = File(
            projectDir,
            "build/generated/sources/annotationProcessor/java/main/org/openrewrite/after/CarMapperImpl.java"
        )
        assertThat(rewrittenGeneratedImpl)
            .describedAs("the generated mapper impl should be rewritten into the renamed package")
            .exists()
        assertThat(rewrittenGeneratedImpl.readText()).contains("package org.openrewrite.after;")
    }
}
