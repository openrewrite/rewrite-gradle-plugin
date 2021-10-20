/*
 * Copyright 2021 the original author or authors.
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

import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Issue
import spock.lang.Unroll

@Unroll
class RewriteRunTest extends RewriteTestBase {

    def "rewriteRun will alter the source file according to the provided active recipe"() {
        given:
        new File(projectDir, "settings.gradle").createNewFile()
        File rewriteYaml = new File(projectDir, "rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = new File(projectDir, "build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFileBefore = writeSource(helloWorldJavaBeforeRefactor)
        File sourceFileAfter = new File(projectDir, "src/main/java/org/openrewrite/after/HelloWorld.java")

        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunMainResult = result.task(":rewriteRun")

        then:
        rewriteRunMainResult.outcome == TaskOutcome.SUCCESS
        !sourceFileBefore.exists()
        sourceFileAfter.exists()
        sourceFileAfter.text == helloWorldJavaAfterRefactor

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    def "Applies recipes to files not inside of source directories"() {
        given:
        new File(projectDir, "settings.gradle").createNewFile()
        File rewriteYaml = new File(projectDir, "rewrite.yml")
        rewriteYaml.text = """\
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.test.GradleWrapper7
                displayName: Use Gradle version 7.1.1
                description: Sets the gradle version to 7.1.1 in gradle/wrapper/gradle-wrapper.properties
                recipeList:
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: distributionUrl
                      newValue: https\\://services.gradle.org/distributions/gradle-7.1.1-bin.zip
            """.stripIndent()

        File gradleWrapperProperties = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        gradleWrapperProperties.getParentFile().mkdirs()
        gradleWrapperProperties.text = """\
                distributionUrl=https\\://services.gradle.org/distributions/gradle-7.0.1-bin.zip
            """.stripIndent()

        File buildGradle = new File(projectDir, "build.gradle")
        buildGradle.text = """\
                 plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                 }
                 
                 rewrite {
                    activeRecipe("org.openrewrite.test.GradleWrapper7")
                 }
                 
                 repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
            """.stripIndent()
        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunMainResult = result.task(":rewriteRun")

        then:
        gradleWrapperProperties.text == "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.1.1-bin.zip\n"

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    def "rewriteRun applies built-in AutoFormat to a multi-project build"() {
        // note, the "output" result of this test is at least somewhat contingent
        // on the current state of what the recipe will perform depending on the version
        // of upstream rewrite used at the time of running this test--
        // the goal of this test is to determine whether changes are applied to subprojects
        // on a multi-project build, not so much about the actual output of the tests.
        given:
        File settings = new File(projectDir, "settings.gradle")
        settings.text = """\
                include("a")
                include("b")
            """.stripIndent()
        File rootBuildGradle = new File(projectDir, "build.gradle")
        rootBuildGradle.text = """\
                plugins {
                    id("org.openrewrite.rewrite")
                }
                
                rewrite {
                    activeRecipe("org.openrewrite.java.format.AutoFormat")
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
            """.stripIndent()
        File aSrcDir = new File(projectDir, "a/src/test/java/com/foo")
        aSrcDir.mkdirs()
        File aTestClass = new File(aSrcDir, "ATestClass.java")
        aTestClass.text = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class ATestClass {
                
                    @Test
                    public void passes() { }
                }
            """.stripIndent()
        File bSrcDir = new File(projectDir, "b/src/test/java/com/foo")
        bSrcDir.mkdirs()
        File bTestClass = new File(bSrcDir, "BTestClass.java")
        bTestClass.text = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class BTestClass {
                
                    @Test
                    public void passes() { }
                }
            """.stripIndent()
        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunResult = result.task(":rewriteRun")
        String aTestClassExpected = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class ATestClass {
                
                    @Test
                    public void passes() {
                    }
                }
        """.stripIndent()
        String bTestClassExpected = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class BTestClass {
                
                    @Test
                    public void passes() {
                    }
                }
        """.stripIndent()
        then:
        rewriteRunResult.outcome == TaskOutcome.SUCCESS
        aTestClass.text == aTestClassExpected
        bTestClass.text == bTestClassExpected

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/33")
    def "rewriteRun applies recipes provided from external dependencies on multi-project builds"() {
        given:
        File settings = new File(projectDir, "settings.gradle")
        settings.text = """\
                include("a")
                include("b")
            """.stripIndent()
        File rootBuildGradle = new File(projectDir, "build.gradle")
        rootBuildGradle.text = """\
                plugins {
                    id("org.openrewrite.rewrite")
                }
                
                rewrite {
                     activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                       url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }
                
                dependencies {
                    rewrite("org.openrewrite.recipe:rewrite-spring:latest.integration")
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
            """.stripIndent()
        File aSrcDir = new File(projectDir, "a/src/test/java/com/foo")
        aSrcDir.mkdirs()
        File aTestClass = new File(aSrcDir, "ATestClass.java")
        aTestClass.text = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class ATestClass {
                
                    @Test
                    public void passes() {
                    }
                }
            """.stripIndent()
        File bSrcDir = new File(projectDir, "b/src/test/java/com/foo")
        bSrcDir.mkdirs()
        File bTestClass = new File(bSrcDir, "BTestClass.java")
        bTestClass.text = """\
                package com.foo;
    
                import org.junit.Test;
                
                public class BTestClass {
                
                    @Test
                    public void passes() {
                    }
                }
            """.stripIndent()
        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunResult = result.task(":rewriteRun")
        String aTestClassExpected = """\
                package com.foo;
    
                import org.junit.jupiter.api.Test;
                
                public class ATestClass {
                
                    @Test
                    public void passes() {
                    }
                }
        """.stripIndent()
        String bTestClassExpected = """\
                package com.foo;
    
                import org.junit.jupiter.api.Test;
                
                public class BTestClass {
                
                    @Test
                    public void passes() {
                    }
                }
        """.stripIndent()
        then:
        rewriteRunResult.outcome == TaskOutcome.SUCCESS
        aTestClass.text == aTestClassExpected
        bTestClass.text == bTestClassExpected

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    def "uses checkstyle configuration"() {
        given:
        File checkstyleXml = new File(projectDir, "config/checkstyle/checkstyle.xml")
        checkstyleXml.getParentFile().mkdirs()
        checkstyleXml.text = """\
                <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.2//EN"
                    "https://checkstyle.org/dtds/configuration_1_2.dtd">
                <module name="Checker">
                    <module name="EqualsAvoidNull">
                        <property name="ignoreEqualsIgnoreCase" value="true" />
                    </module>
                </module>
            """.stripIndent()

        File rootBuildGradle = new File(projectDir, "build.gradle")
        rootBuildGradle.text = """\
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
            """.stripIndent()
        File aSrcDir = new File(projectDir, "src/main/java/com/foo")
        aSrcDir.mkdirs()
        File aClass = new File(aSrcDir, "A.java")
        aClass.text = """\
                package com.foo;
                
                public class A {
                    {
                        String s = null;
                        if(s.equals("test")) {}
                        if(s.equalsIgnoreCase("test")) {}
                    }
                }
            """.stripIndent()
        String aClassExpected = """\
                package com.foo;
                
                public class A {
                    {
                        String s = null;
                        if("test".equals(s)) {}
                        if(s.equalsIgnoreCase("test")) {}
                    }
                }
            """.stripIndent()


        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunResult = result.task(":rewriteRun")

        then:
        rewriteRunResult.outcome == TaskOutcome.SUCCESS
        aClass.text == aClassExpected

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    def "can apply non-java recipe to files inside and outside of java resources directories"() {
        given:
        new File(projectDir, "settings.gradle").createNewFile()

        File rewriteYaml = new File(projectDir, "rewrite.yml")
        rewriteYaml.text = """\
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.RenameSam
                displayName: Rename property keys
                description: Renames property keys named 'sam' to 'samuel'
                recipeList:
                  - org.openrewrite.properties.ChangePropertyKey:
                      oldPropertyKey: sam
                      newPropertyKey: samuel
            """.stripIndent()

        File buildGradleFile = new File(projectDir, "build.gradle")
        buildGradleFile.text = """\
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
            """.stripIndent()

        String propertiesFileText = "sam=true\n"
        String propertiesTextExpected = "samuel=true\n"
        File propertiesFileNotInSourceSet = new File(projectDir, "outside-of-sourceset.properties")
        propertiesFileNotInSourceSet.text = propertiesFileText
        File propertiesFileInSourceSet = new File(projectDir, "src/main/resources/in-sourceset.properties")
        propertiesFileInSourceSet.getParentFile().mkdirs()
        propertiesFileInSourceSet.text = propertiesFileText

        when:
        def result = gradleRunner(gradleVersion, "rewriteRun").build()
        def rewriteRunResult = result.task(":rewriteRun")

        then:
        rewriteRunResult.outcome == TaskOutcome.SUCCESS
        propertiesFileNotInSourceSet.text == propertiesTextExpected
        propertiesFileInSourceSet.text == propertiesTextExpected

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }
}
