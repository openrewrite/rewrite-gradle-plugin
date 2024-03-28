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
import org.junit.jupiter.api.condition.DisabledIf
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.Issue
import java.io.File
import java.nio.file.Path

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
    fun `rewriteRun applies recipe to a multi-project build`(
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
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.FormatAndAddProperty
                recipeList:
                  - org.openrewrite.java.format.AutoFormat
                  - org.openrewrite.properties.ChangePropertyKey:
                      oldPropertyKey: foo
                      newPropertyKey: bar
            """)
            buildGradle("""
                plugins {
                    id("org.openrewrite.rewrite")
                    id("java")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.FormatAndAddProperty")
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
                        implementation(project(":"))
                        implementation("junit:junit:4.12")
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
                    propertiesFile("test.properties", "foo=baz\n")
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

        val propertiesFile = File(projectDir, "a/src/test/resources/test.properties")
        assertThat(propertiesFile.readText()).isEqualTo("bar=baz\n")
    }

    @Test
    fun `resources in subproject committed to git are correctly processed`(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.ChangeFooToBar
                recipeList:
                  - org.openrewrite.properties.ChangePropertyKey:
                      oldPropertyKey: foo
                      newPropertyKey: bar
            """)
            buildGradle("""
                plugins {
                    id("org.openrewrite.rewrite")
                    id("java")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.ChangeFooToBar")
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
                        implementation(project(":"))
                        implementation("junit:junit:4.12")
                    }
                }
            """.trimIndent())
            subproject("a") {
                sourceSet("main") {
                    propertiesFile("test.properties", "foo=baz\n")
                }
            }
        }
        commitFilesToGitRepo(projectDir)

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val propertiesFile = File(projectDir, "a/src/main/resources/test.properties")
        assertThat(propertiesFile.readText()).isEqualTo("bar=baz\n")
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
                    activeRecipe("org.openrewrite.staticanalysis.UnnecessaryParentheses")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
                
                dependencies {
                    rewrite("org.openrewrite.recipe:rewrite-static-analysis:1.0.4")
                }
            """)
            sourceSet("main") {
                java("""
                    package com.foo;
                    
                    public class A {
                        {
                            String s = null;
                            if((s.equals("test"))) {}
                            if(s.equalsIgnoreCase(("test"))) {}
                        }
                    }
                """)
            }
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!

        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val aFile = File(projectDir, "src/main/java/com/foo/A.java")
        //language=java
        val aClassExpected = """
            package com.foo;
            
            public class A {
                {
                    String s = null;
                    if(s.equals("test")) {}
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
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.AddGradleWrapper
                displayName: Adds a Gradle wrapper
                description: Add wrapper for gradle version 7.4.2
                recipeList:
                  - org.openrewrite.gradle.UpdateGradleWrapper:
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
            distributionSha256Sum=29e49b10984e585d8118b7d0bc452f944e386458df27371b49b4ac1dec4b7fda
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent()
        val gradlePropsFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        assertThat(gradlePropsFile.readText()).isEqualTo(expectedProps)
        val gradleWrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        assertThat(gradleWrapperJar.exists()).isTrue
    }

    @Test
    fun gradleDependencyManagement(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.RemoveJacksonCore
                displayName: Remove jackson-core
                description: Remove jackson-core
                recipeList:
                  - org.openrewrite.gradle.RemoveDependency:
                      groupId: com.fasterxml.jackson.core
                      artifactId: jackson-core
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
                
                
                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
                    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.test.RemoveJacksonCore")
                }
            """)
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(projectDir.resolve("build.gradle").readText())
            //language=groovy
            .isEqualTo("""
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
            
            
            dependencies {
                implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
            }
            
            rewrite {
                activeRecipe("org.openrewrite.test.RemoveJacksonCore")
            }
            """.trimIndent())
    }


    @DisabledIf("lessThanGradle6_8")
    @Test
    fun dependencyRepositoriesDeclaredInSettings(
        @TempDir projectDir: File
    ) {
        gradleProject(projectDir) {
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.UpgradeJacksonCore
                displayName: Remove jackson-core
                description: Remove jackson-core
                recipeList:
                  - org.openrewrite.gradle.UpgradeDependencyVersion:
                      groupId: com.fasterxml.jackson.core
                      artifactId: jackson-core
                      newVersion: 2.16.0
            """)
            settingsGradle("""
                dependencyResolutionManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                        maven {
                           url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                        }
                    }
                }
            """)
            buildGradle("""
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                }
                
                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-core:2.15.1")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.test.UpgradeJacksonCore")
                }
            """)
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(projectDir.resolve("build.gradle").readText())
            //language=groovy
            .isEqualTo("""
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                }
                
                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-core:2.16.0")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.test.UpgradeJacksonCore")
                }
                """.trimIndent())
    }

    @Test
    fun mergeConfiguredAndAutodetectedStyles(@TempDir projectDir: File) {
        gradleProject(projectDir) {
            propertiesFile("gradle.properties", "systemProp.rewrite.activeStyles=org.openrewrite.testStyle")
            rewriteYaml("""
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

    @DisabledIf("lessThanGradle6_1")
    @Test
    fun kotlinSource(@TempDir projectDir: File) {
        gradleProject(projectDir) {
            buildGradle(
                """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version("1.8.0")
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
                    activeRecipe("org.openrewrite.test.FindString")
                }
            """)
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.FindString
                displayName: Find kotlin strings
                description: Finds kotlin strings.
                recipeList:
                  - org.openrewrite.java.search.FindTypes:
                      fullyQualifiedTypeName: kotlin.String
            """)
            sourceSet("main") {
                kotlin("""
                    package com.foo
                    
                    class A {
                        fun foo(s: String): String {
                            return s
                        }
                    }
                """)
            }
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val aFile = projectDir.resolve("src/main/kotlin/com/foo/A.kt")
        assertThat(aFile.readText().contains("/*~~>*/")).isTrue
    }

    @Test
    fun `build root and repository root do not need to be the same`(@TempDir repositoryRoot: File) {
        repositoryRoot.apply{
            resolve(".git").apply{
                mkdirs()
                resolve("HEAD").apply{
                    createNewFile()
                    writeText("ref: refs/heads/main")
                }
                resolve("objects").apply{
                    mkdir()
                }
                resolve("refs").apply{
                    mkdir()
                }
                resolve("reftable").apply{
                    mkdir()
                }
            }
        }
        val buildRoot = repositoryRoot.resolve("test-project").apply{  mkdirs() }
        gradleProject(buildRoot) {
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
                java("""
                    package org.openrewrite.before;
                
                    import java.util.ArrayList;
                    import java.util.List;
                    
                    public class HelloWorld {
                        public static void main(String[] args) {
                            System.out.print("Hello");
                                System.out.println(" world");
                        }
                    }
                """)
            }
        }

        val result = runGradle(buildRoot, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val javaFile = buildRoot.resolve("src/main/java/org/openrewrite/before/HelloWorld.java")
        assertThat(javaFile.readText())
            //language=java
            .isEqualTo("""
                package org.openrewrite.before;
                
                import java.util.ArrayList;
                import java.util.List;
                
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.print("Hello");
                        System.out.println(" world");
                    }
                }
                """.trimIndent()
            )
    }

    @DisabledOnOs(OS.WINDOWS) // A file handle I haven't been able to track down is left open, causing JUnit to fail to clean up the directory on Windows
    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/176")
    @Test
    fun runRecipeFromProjectDependency(@TempDir projectDir: File) {
        gradleProject(projectDir) {
            settingsGradle("""
                rootProject.name = 'multi-project-recipe'

                include("recipe")
                include("product")
            """)
            subproject("recipe") {
                buildGradle("""
                    plugins { 
                        id("java")
                    }
                """)
                sourceSet("main") {
                    yamlFile("META-INF/rewrite/recipe.yml", """
                        type: specs.openrewrite.org/v1beta/recipe
                        name: com.example.TextToSam
                        displayName: Changes contents of sam.txt
                        description: Change contents of sam.txt to "sam"
                        preconditions:
                          - org.openrewrite.FindSourceFiles:
                              filePattern: "**/sam.txt"
                        recipeList:
                          - org.openrewrite.text.ChangeText:
                              toText: sam
                    """)
                }
            }
            subproject("product") {
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
                    
                    rewrite {
                        activeRecipe("com.example.TextToSam")
                    }
                    
                    dependencies {
                        rewrite(project(":recipe"))
                    }
                """)
                textFile("sam.txt", "notsam")
                textFile("jonathan.txt", "jonathan")
            }
        }

        val result = runGradle(projectDir, "rewriteRun")
        val rewriteRunResult = result.task(":product:rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val samFile = projectDir.resolve("product/sam.txt")
        assertThat(samFile.readText()).isEqualTo("sam")

        val jonathanFile = projectDir.resolve("product/jonathan.txt")
        assertThat(jonathanFile.readText())
            .`as`("Applicability test should have prevented this file from being altered")
            .isEqualTo("jonathan")
    }

    @Test
    fun overlappingSourceSet(@TempDir buildRoot: File) {
        gradleProject(buildRoot) {
            rewriteYaml("""
              type: specs.openrewrite.org/v1beta/recipe
              name: org.openrewrite.Overlaps
              displayName: Find overlaps
              description: Find lombok SneakyThrows annotation and duplicate source files
              recipeList:
                - org.openrewrite.java.search.FindTypes:
                    fullyQualifiedTypeName: lombok.SneakyThrows
                - org.openrewrite.FindDuplicateSourceFiles
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
                
                sourceSets {
                    create("overlapping") {
                        java.srcDir("src/test/java")
                    }
                }

                rewrite {
                    activeRecipe("org.openrewrite.Overlaps")
                }
                
                dependencies {
                    rewrite("org.openrewrite.recipe:rewrite-all:latest.integration")
                    testImplementation("org.projectlombok:lombok:latest.release")
                }
            """)
            sourceSet("test") {
                java("""
                    package com.foo;
                    
                    import lombok.SneakyThrows;
                    
                    public class ATest {
                    
                        @SneakyThrows
                        public void fail() { 
                        }
                    }
                """)
            }
        }

        val result = runGradle(buildRoot, "rewriteRun")
        val rewriteRunResult = result.task(":rewriteRun")!!
        assertThat(rewriteRunResult.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val javaFile = buildRoot.resolve("src/test/java/com/foo/ATest.java")
        assertThat(javaFile.readText())
            //language=java
            .isEqualTo("""
                package com.foo;
                
                import lombok.SneakyThrows;
                
                public class ATest {
                
                    @/*~~>*/SneakyThrows
                    public void fail() { 
                    }
                }
                """.trimIndent()
            )
    }

    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/128")
    @Test
    fun deleteFromGitRepository(@TempDir projectDir: File) {
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
                    activeRecipe("com.test.DeleteYamlKey")
                }
            """.trimIndent())
            rewriteYaml("""
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.DeleteYamlKey
                displayName: Delete yaml
                description: Delete yaml
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/foo.yml"
            """.trimIndent())
            sourceSet("main") {
                yamlFile("foo.yml", """
                  foo: bar
                """.trimIndent())
            }
        }
        gitInit(projectDir.toPath(), "test-project")
        val result = runGradle(projectDir, "rewriteRun")
        val task = result.task(":rewriteRun")!!
        assertThat(task.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val yamlFile = projectDir.resolve("src/main/resources/foo.yml")
        assertThat(yamlFile.exists()).isFalse()
        assertThat(projectDir.resolve("src/main/resources")).doesNotExist()
    }

    fun gitInit(repositoryPath: Path, repositoryName: String) {
        try {
            org.openrewrite.jgit.api.Git.init().setDirectory(repositoryPath.toFile()).call().use { git ->
                //This is required for the maven and gradle plugin. This requirement needs to be removed
                git.remoteSetUrl().setRemoteName("origin").setRemoteUri(
                    org.openrewrite.jgit.transport.URIish().setHost("git@github.com").setPort(80)
                        .setPath("acme/$repositoryName.git")
                )
                    .call()
                git.add().addFilepattern(".").call()
                git.commit().setMessage("init commit").call()
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Test
    fun `parse gradle wrapper properties`(
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
                activeRecipe("org.openrewrite.gradle.FindDistributionUrl")
            }
            """.trimIndent())
            propertiesFile("gradle/wrapper/gradle-wrapper.properties", """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-6.8.3-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
            """.trimIndent())
            rewriteYaml("""
            type: specs.openrewrite.org/v1beta/recipe
            name: org.openrewrite.gradle.FindDistributionUrl
            recipeList:
              - org.openrewrite.properties.search.FindProperties:
                  propertyKey: distributionUrl
            """.trimIndent())
        }
        val result = runGradle(projectDir, "rewriteRun")
        val task = result.task(":rewriteRun")!!
        assertThat(task.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val propertiesFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        assertThat(propertiesFile.readText())
            .isEqualTo("""
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=~~>https\://services.gradle.org/distributions/gradle-6.8.3-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.trimIndent()
            )
    }
}
