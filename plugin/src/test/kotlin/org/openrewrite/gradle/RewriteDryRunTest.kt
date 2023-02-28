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

class RewriteDryRunTest : RewritePluginTest {
    @Test
    fun `rewriteDryRun runs successfully without modifying source files`(
        @TempDir projectDir: File
    ) {
        //language=java
        val helloWorld = """
            package org.openrewrite.before;
            
            public class HelloWorld { public static void sayGoodbye() {System.out.println("Hello world");
                }public static void main(String[] args) {   sayGoodbye(); }
            }
        """.trimIndent()
        gradleProject(projectDir) {
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.gradle.SayHello
                recipeList:
                  - org.openrewrite.java.ChangeMethodName:
                      methodPattern: org.openrewrite.before.HelloWorld sayGoodbye()
                      newMethodName: sayHello
                  - org.openrewrite.java.ChangePackage:
                      oldPackageName: org.openrewrite.before
                      newPackageName: org.openrewrite.after
            """)
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
                    activeRecipe("org.openrewrite.gradle.SayHello", "org.openrewrite.java.format.AutoFormat")
                }
            """)
            sourceSet("main") {
                java(helloWorld)
            }
        }
        val result = runGradle(projectDir, "rewriteDryRun")
        val rewriteDryRunResult = result.task(":rewriteDryRun")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(File(projectDir, "src/main/java/org/openrewrite/before/HelloWorld.java")
            .readText()).isEqualTo(helloWorld)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch").exists()).isTrue
    }

    @Test
    fun `A recipe with optional configuration can be activated directly`(
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
            """)
            sourceSet("main") {
                java("""
                    package org.openrewrite.before;
                
                    import java.util.ArrayList;
                    
                    import java.util.List;
                    
                    public class HelloWorld {
                        public static void sayHello() {
                            System.out.println("Hello world");
                        }
                    
                        public static void main(String[] args) {
                            sayHello();
                        }
                    }
                """)
            }
        }

        val result = runGradle(projectDir, "rewriteDryRun", "-DactiveRecipe=org.openrewrite.java.OrderImports")
        val rewriteDryRunResult = result.task(":rewriteDryRun")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch").exists()).isTrue
    }
}
