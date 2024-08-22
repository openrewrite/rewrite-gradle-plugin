/*
 * Copyright 2024 the original author or authors.
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
import org.junit.jupiter.api.condition.DisabledIf
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.openrewrite.Issue
import java.io.File
import java.nio.file.Path

class RewriteDryRunTest : RewritePluginTest {
    @TempDir
    lateinit var projectDir: File

    override fun taskName(): String = "rewriteDryRun"

    @Test
    fun `rewriteDryRun runs successfully without modifying source files`() {
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
                description: Test.
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
        val result = runGradle(projectDir, taskName())
        val rewriteDryRunResult = result.task(":${taskName()}")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(File(projectDir, "src/main/java/org/openrewrite/before/HelloWorld.java")
            .readText()).isEqualTo(helloWorld)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch").exists()).isTrue
    }

    @Test
    fun `A recipe with optional configuration can be activated directly`() {
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

        val result = runGradle(projectDir, taskName(), "-DactiveRecipe=org.openrewrite.java.OrderImports")
        val rewriteDryRunResult = result.task(":${taskName()}")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch").exists()).isTrue
    }

    @DisabledIf("lessThanGradle6_1")
    @Test
    fun multiplatform() {
        gradleProject(projectDir) { 
            buildGradle("""
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                    id("org.jetbrains.kotlin.multiplatform") version "1.8.0"
                }
                group = "org.example"
                version = "1.0-SNAPSHOT"
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }

                kotlin {
                    jvm {
                        jvmToolchain(8)
                        withJava()
                    }

                    sourceSets {
                        commonMain {
                            dependencies {
                            }
                        }
                        commonTest {
                            dependencies {
                            }
                        }
                        jvmMain {
                        }
                        jvmTest {
                        }
                    }
                }
            """)
            settingsGradle("""
                pluginManagement {
                    repositories {
                        mavenLocal()
                        maven { url = uri("https://plugins.gradle.org/m2") }
                        gradlePluginPortal()
                    }
                }
                rootProject.name = "example"
            """)
            sourceSet("commonMain") { 
                kotlin("""
                    class HelloWorld {
                        fun sayHello() {
                            println("Hello world")
                        }
                    }
                """)
            }
        }
        val result = runGradle(projectDir, taskName(), "-DactiveRecipe=org.openrewrite.kotlin.FindKotlinSources")
        val rewriteDryRunResult = result.task(":${taskName()}")!!

        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch").exists()).isTrue
    }

    // The configuration cache works on Gradle 6.6+, but rewrite-gradle-plugin uses notCompatibleWithConfigurationCache,
    // which is only available on Gradle 7.4+.
    @DisabledIf("lessThanGradle7_4")
    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/227")
    @Test
    fun `rewriteDryRun is compatible with the configuration cache`() {
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
        val result = runGradle(projectDir, taskName(), "--configuration-cache")
        val rewriteDryRunResult = result.task(":${taskName()}")!!
        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @ParameterizedTest
    @ValueSource(strings=["8.5.0", "7.0.4", "4.2.2"])
    fun androidDefaultSourceSets(pluginVersion: String) {
        androidProject(projectDir.toPath()) {
            buildGradle("""
                plugins {
                    id("com.android.application") version "${pluginVersion}"
                    id("org.openrewrite.rewrite")
                }

                repositories {
                    google()
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }

                group = "org.example"
                version = "1.0-SNAPSHOT"

                android {
                    namespace = "example"
                    compileSdkVersion 30
                }
            """)
            sourceSet("main") {
                java("""
                    import java.util.List;
                    import java.util.Collections;

                    class HelloWorld {
                        HelloWorld() {
                            super();
                        }
                    }
                """)
            }
        }
        val result = runGradle(projectDir, taskName(), "-DactiveRecipe=org.openrewrite.java.OrderImports")
        val rewriteDryRunResult = result.task(":${taskName()}")!!

        assertThat(rewriteDryRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(File(projectDir, "build/reports/rewrite/rewrite.patch")).exists()
    }
}
