/*
 * Copyright 2020 the original author or authors.
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

class RewritePluginTest extends RewriteTestBase {

    String rewriteYamlText =  """\
            ---
            type: specs.openrewrite.org/v1beta/visitor
            name: org.openrewrite.gradle.SayHello
            visitors:
              - org.openrewrite.java.ChangeMethodName:
                  method: org.openrewrite.gradle.HelloWorld sayGoodbye()
                  name: sayHello
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: org.openrewrite.testProfile
            include:
              - 'org.openrewrite.gradle.SayHello'
            """.stripIndent()
    String buildGradleFileText = """\
            plugins {
                id("java")
                id("org.openrewrite.rewrite")
            }
            
            rewrite {
                configFile = "rewrite-config.yml"
                activeRecipe("org.openrewrite.testProfile")
            }
            """.stripIndent()
    String HelloWorldJavaBeforeRefactor = """\
            package org.openrewrite.gradle;
            
            public class HelloWorld {
                public static void sayGoodbye() {
                    System.out.println("Hello world");
                }
                public static void main(String[] args) {
                    sayGoodbye();
                }
            }
            """.stripIndent()
    String HelloWorldJavaAfterRefactor = """\
            package org.openrewrite.gradle;
            
            public class HelloWorld {
                public static void sayHello() {
                    System.out.println("Hello world");
                }
                public static void main(String[] args) {
                    sayHello();
                }
            }
            """.stripIndent()


    def "rewriteWarn task will run as part of a normal Java Build"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "build").build()

        def rewriteWarnMainResult = result.task(":rewriteWarnMain")
        def rewriteWarnTestResult = result.task(":rewriteWarnTest")


        then:
        rewriteWarnMainResult.outcome == TaskOutcome.SUCCESS
        // The "warn" task should not have touched the source file and the "fix" task shouldn't have run
        sourceFile.text == HelloWorldJavaBeforeRefactor
        // With no test source in this project any of these are potentially reasonable results
        // Ultimately NO_SOURCE is probably the most appropriate, but in this early stage of development SUCCESS is acceptable
        rewriteWarnTestResult.outcome == TaskOutcome.SUCCESS || rewriteWarnTestResult.outcome == TaskOutcome.NO_SOURCE || rewriteWarnTestResult.outcome == TaskOutcome.SKIPPED
    }

    def "rewriteFix will alter the source file according to the provided active recipe"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "rewriteFixMain").build()
        def rewriteFixMainResult = result.task(":rewriteFixMain")

        then:
        rewriteFixMainResult.outcome == TaskOutcome.SUCCESS
        sourceFile.text == HelloWorldJavaAfterRefactor
    }

    def "rewriteFix works on multi-project builds"() {
        given:
        File settings = projectDir.newFile("settings.gradle")
        settings.text = """
                include("a")
                include("b")
            """.stripIndent()
        File rootBuildGradle = projectDir.newFile("build.gradle")
        rootBuildGradle.text = """
                buildscript {
                    repositories {
                        maven {
                            url "https://plugins.gradle.org/m2/"
                        }
                    }
                    dependencies {
                        classpath "gradle.plugin.org.openrewrite:plugin:+"
                    }
                }
                
                subprojects {
                    apply plugin: "java"
                    apply plugin: "org.openrewrite.rewrite"
                
                    repositories {
                        jcenter()
                    }
                
                    dependencies {
                        testImplementation("junit:junit:4.12")
                        compileOnly("org.openrewrite.recipe:rewrite-testing-frameworks:+")
                    }
                }
            """.stripIndent()
        File aSrcDir = projectDir.newFolder("a", "src", "test", "java", "com", "foo");
        File aTestClass = new File(aSrcDir, "ATestClass.java")
        aTestClass.text = """
                package com.foo;
    
                import org.junit.Test;
                
                public class ATestClass {
                    @Test
                    public void passes() { }
                }
            """.stripIndent()
        File bSrcDir = projectDir.newFolder("b", "src", "test", "java", "com", "foo");
        File bTestClass = new File(bSrcDir, "BTestClass.java")
        bTestClass.text = """
                package com.foo;
    
                import org.junit.Test;
                
                public class BTestClass {
                    @Test
                    public void passes() { }
                }
            """.stripIndent()
        when:
        def result = gradleRunner("6.5.1", "rewriteFix").build()
        def aRewriteFixMainResult = result.task(":a:rewriteFixTest")
        def bRewriteFixMainResult = result.task(":b:rewriteFixTest")

        then:
        aRewriteFixMainResult.outcome == TaskOutcome.SUCCESS
        bRewriteFixMainResult.outcome == TaskOutcome.SUCCESS
    }

    def "rewriteDiscover will print some stuff"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "rewriteDiscoverMain").build()
        def rewriteDiscoverResult = result.task(":rewriteDiscoverMain")

        then:
        rewriteDiscoverResult.outcome == TaskOutcome.SUCCESS
    }
}
