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
@file:Suppress("UnusedProperty", "GrPackage", "GrMethodMayBeStatic", "ConstantConditions", "StatementWithEmptyBody",
    "InfiniteRecursion"
)

package org.openrewrite.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RewriteRunTest : RewritePluginTest {

    @Test
    fun `rewrite is isolated from conflicting versions of jackson on the classpath`(
        @TempDir projectDir: File
    ) {
        // This test doesn't definitively _prove_ that our isolation is sufficient
        // Gradle provides no control over how it arbitrarily orders its classpath
        // So even if isolation isn't working at all, this could pass if it happens to put the rewrite's required version
        // of jackson first on the classpath.
        gradleProject(projectDir) {
            buildGradle("""
                buildscript {
                    repositories {
                        mavenCentral()
                    }
                    dependencies {
                        classpath("com.fasterxml.jackson.core:jackson-core:2.7.9")
                    }
                }
                plugins {
                    id("java")
                    // nebula brings in jackson 2.5.4
                    id("nebula.integtest") version "7.0.9" apply false 
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
                    activeRecipe("org.openrewrite.java.format.AutoFormat")
                }
            """)
            sourceSet("test") {
                java("""
                    package com.foo;
                    
                    public class ATestClass {
                    public void passes() { }
                    }
                """)
            }
        }

        val buildResult = runGradle(projectDir, "rewriteRun")
        val taskResult = buildResult.task(":rewriteRun")!!
        assertThat(taskResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `rewriteRun will alter the source file according to the provided active recipe`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                ---
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
                java("""
                    package org.openrewrite.before;
                    
                    public class HelloWorld { public static void sayGoodbye() {System.out.println("Hello world");
                        }public static void main(String[] args) {   sayGoodbye(); }
                    }
                """)
            }
        }
        assertThat(File(projectDir, "build.gradle").exists()).isTrue
            val buildResult = runGradle(projectDir, "rewriteRun")
            val taskResult = buildResult.task(":rewriteRun")!!


            assertThat(taskResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
            val sourceFileAfter = File(projectDir, "src/main/java/org/openrewrite/after/HelloWorld.java")
            assertThat(sourceFileAfter.exists()).isTrue
            val expected =
                //language=java
                """
                package org.openrewrite.after;

                public class HelloWorld {
                    public static void sayHello() {
                        System.out.println("Hello world");
                    }

                    public static void main(String[] args) {
                        sayHello();
                    }
                }
            """.trimIndent()
            assertThat(sourceFileAfter.readText()).isEqualTo(expected)
    }

    @Test
    fun `rewriteRun applies built-in AutoFormat to a multi-project build`(
        @TempDir projectDir: File
    ) {
        val bTestClassExpected = """
                package com.foo;
    
                import org.junit.Test;
                
                public class BTestClass {
                
                    @Test
                    public void passes() { }
                }
        """.trimIndent()
        gradleProject(projectDir) {
            buildGradle("""
                plugins {
                    id("org.openrewrite.rewrite")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.java.format.AutoFormat")
                    exclusion("**/BTestClass.java")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
                
                subprojects {
                    apply plugin: "java"
                
                    repositories {
                        mavenCentral()
                    }
                
                    dependencies {
                        testImplementation("junit:junit:4.12")
                    }
                }
            """)
            subproject("a") {
                sourceSet("test") {
                    java("""
                        package com.foo;
            
                        import org.junit.Test;
                        
                        public class ATestClass {
                        
                            @Test
                            public void passes() { }
                        }
                    """)
                }
            }
            subproject("b") {
                sourceSet("test") {
                    java(bTestClassExpected)
                }
            }
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        //language=java
        val aTestClassExpected = """
            package com.foo;

            import org.junit.Test;
            
            public class ATestClass {
            
                @Test
                public void passes() {
                }
            }
        """.trimIndent()
        val aTestClassFile = File(projectDir, "a/src/test/java/com/foo/ATestClass.java")
        assertThat(aTestClassFile.readText()).isEqualTo(aTestClassExpected)
        val bTestClassFile = File(projectDir, "b/src/test/java/com/foo/BTestClass.java")
        assertThat(bTestClassFile.readText()).isEqualTo(bTestClassExpected)
    }

    @Suppress("ClassInitializerMayBeStatic", "StatementWithEmptyBody", "ConstantConditions")
    @Test
    fun `Checkstyle configuration is applied as a style`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            checkstyleXml("""
                <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.2//EN"
                    "https://checkstyle.org/dtds/configuration_1_2.dtd">
                <module name="Checker">
                    <module name="EqualsAvoidNull">
                        <property name="ignoreEqualsIgnoreCase" value="true" />
                    </module>
                </module>
            """)
            buildGradle("""
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                    id("checkstyle")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.java.cleanup.EqualsAvoidsNull")
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
                    package com.foo;
                    
                    public class A {
                        {
                            String s = null;
                            if(s.equals("test")) {}
                            if(s.equalsIgnoreCase("test")) {}
                        }
                    }
                """)
            }
        }

        val result = runGradle(projectDir,"rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!

        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val aFile = File(projectDir, "src/main/java/com/foo/A.java")
        //language=java
        val aClassExpected = """
            package com.foo;
            
            public class A {
                {
                    String s = null;
                    if("test".equals(s)) {}
                    if(s.equalsIgnoreCase("test")) {}
                }
            }
        """.trimIndent()
        assertThat(aFile.readText()).isEqualTo(aClassExpected)
    }

    @Test
    fun `can apply non-java recipe to files inside and outside of java resources directories`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.RenameSam
                displayName: Rename property keys
                description: Renames property keys named 'sam' to 'samuel'
                recipeList:
                  - org.openrewrite.properties.ChangePropertyKey:
                      oldPropertyKey: sam
                      newPropertyKey: samuel
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
                    activeRecipe("com.example.RenameSam")
                }
            """)
            propertiesFile("outside-of-sourceset.properties", "sam=true\n")
            sourceSet("main") {
                propertiesFile("in-sourceset.properties", "sam=true\n")
            }
        }
        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val propertiesTextExpected = "samuel=true\n"
        assertThat(File(projectDir, "outside-of-sourceset.properties").readText()).isEqualTo(propertiesTextExpected)
        assertThat(File(projectDir, "src/main/resources/in-sourceset.properties").readText()).isEqualTo(propertiesTextExpected)
    }

    @Test
    fun `Recipes that generate sources have those sources written out to disk successfully`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.AddGradleWrapper
                displayName: Adds a Gradle wrapper
                description: Add wrapper for gradle version 7.4.2
                recipeList:
                  - org.openrewrite.gradle.AddGradleWrapper:
                      version: "7.4.2"
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
                    activeRecipe("org.openrewrite.test.AddGradleWrapper")
                }
            """)
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val gradlew = File(projectDir, "gradlew")
        assertThat(gradlew.readText()).isNotEmpty
        val gradlewBat = File(projectDir, "gradlew.bat")
        assertThat(gradlewBat.readText()).isNotEmpty
        // language=properties
        val expectedProps = """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\://services.gradle.org/distributions/gradle-7.4.2-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
        """.trimIndent()
        val gradlePropsFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        assertThat(gradlePropsFile.readText()).isEqualTo(expectedProps)
        val gradleWrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        assertThat(gradleWrapperJar.exists()).isTrue
    }

    @Test
    fun gradleScriptWhichDefinesMethod(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.RenameBuildGradle
                displayName: Rename build.gradle to build.gradle.kts
                description: Rename build.gradle to build.gradle.kts
                recipeList:
                  - org.openrewrite.RenameFile:
                      fileMatcher: build.gradle
                      fileName: build.gradle.kts
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
                
                def foo() {}
                class A {}
                
                rewrite {
                    activeRecipe("org.openrewrite.test.RenameBuildGradle")
                }
            """)
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(projectDir.resolve("build.gradle.kts").exists()).isTrue
    }

    @Test
    fun mergeConfiguredAndAutodetectedStyles(@TempDir projectDir: File) {
        gradleProject(projectDir) {
            propertiesFile("gradle.properties", "systemProp.rewrite.activeStyles=org.openrewrite.testStyle")
            rewriteYaml("""
                ---
                type: specs.openrewrite.org/v1beta/style
                name: org.openrewrite.testStyle
                styleConfigs:
                  - org.openrewrite.java.style.TabsAndIndentsStyle:
                      useTabCharacter: true
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
                    activeRecipe("org.openrewrite.java.format.AutoFormat")
                }
            """)
            sourceSet("main") {
                // Uses spaces, to be converted to tabs
                java("""
                    package com.foo;
                    
                    class A {
                        void bar() {
                            System.out.println("Hello world");
                            // Autodetect should determine no spaces before if-statement parens
                            if(true) {}
                            if(true) {}
                        }
                    }
                """)
            }
        }
        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val aFile = projectDir.resolve("src/main/java/com/foo/A.java")
        //language=java
        val expected = """
            package com.foo;

            class A {
            	void bar() {
            		System.out.println("Hello world");
            		// Autodetect should determine no spaces before if-statement parens
            		if(true) {
            		}
            		if(true) {
            		}
            	}
            }
        """.trimIndent()
        val aText = aFile.readText()

        assertThat(aText).isEqualTo(expected)
    }

    @Test
    fun reformatToIntelliJStyle(@TempDir projectDir: File) {
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
                    activeRecipe("org.openrewrite.java.format.AutoFormat")
                    activeStyle("org.openrewrite.java.IntelliJ")
                }
            """)
            sourceSet("main") {
                java("""
                    package com.foo;
                    
                    class A { 
                      void bar() {
                        System.out.println("Hello world");
                        if(true) {
                        }
                        if(true) {
                        }
                      }
                      void baz() {
                        bar();
                        if(true) {
                          baz();
                        }
                      }
                    }
                """)
            }
        }
        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val aFile = projectDir.resolve("src/main/java/com/foo/A.java")
        //language=java
        val expected = """
            package com.foo;
            
            class A {
                void bar() {
                    System.out.println("Hello world");
                    if (true) {
                    }
                    if (true) {
                    }
                }
            
                void baz() {
                    bar();
                    if (true) {
                        baz();
                    }
                }
            }
        """.trimIndent()
        val aText = aFile.readText()

        assertThat(aText).isEqualTo(expected)
    }

    @Test
    fun groovySourceGetsTypesFromJavaSource(@TempDir projectDir: File) {
        gradleProject(projectDir) {
            rewriteYaml("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.FindA
                displayName: Rename build.gradle to build.gradle.kts
                description: Rename build.gradle to build.gradle.kts
                recipeList:
                  - org.openrewrite.java.search.FindTypes:
                      fullyQualifiedTypeName: com.foo.A
            """)
            buildGradle("""
                plugins {
                    id("groovy")
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
                    activeRecipe("org.openrewrite.test.FindA")
                }
                
                dependencies {
                    implementation(localGroovy())
                }
                
            """)
            sourceSet("main") {
                java("""
                    package com.foo;
                    
                    public class A { 
                        public static void foo() {
                        }
                    }
                """)
                groovyClass("""
                    package com.foo
                    
                    class B {
                        def bar() {
                            new A().foo()
                        }
                    }
                """)
            }
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val bFile = projectDir.resolve("src/main/groovy/com/foo/B.groovy")
        //language=groovy
        val bExpected = """
            package com.foo
            
            class B {
                def bar() {
                    new /*~~>*/A().foo()
                }
            }
        """.trimIndent()
        assertThat(bFile.readText()).isEqualTo(bExpected)
    }
}

