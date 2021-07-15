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

import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import spock.lang.Specification
import spock.lang.TempDir

import java.lang.management.ManagementFactory

/**
 * Because of how the Gradle Test Kit manages the classpath of the project under test, these may fail when run from an IDE.
 * To run & debug these tests from IntelliJ ensure you're delegating test execution to Gradle:
 *      Settings > Build, Execution, Deployment > Build Tools > Gradle > Run tests using Gradle
 *
 * That should be all you have to do.
 * If breakpoints within your plugin aren't being hit try adding -Dorg.gradle.testkit.debug=true to the arguments and
 * connecting a remote debugger on port 5005.
 */
class RewriteTestBase extends Specification {
    static final List<String> GRADLE_VERSIONS_UNDER_TEST = gradleVersionsUnderTest()

    @TempDir
    File projectDir

    @TempDir
    File tempDir

    @SuppressWarnings("GroovyAssignabilityCheck")
    File writeSource(String source, String sourceSet = "main") {
        String packageName = (source =~ /package\s+([\w.]+)/)[0][1]
        String className = (source =~ /(class|interface)\s+(\w+)\s+/)[0][2]
        String sourceFilePackage = "src/$sourceSet/java/${packageName.replace('.', '/')}"
        new File(projectDir, sourceFilePackage).mkdirs()
        def file = new File(projectDir, "$sourceFilePackage/${className}.java")
        file << source
        return file
    }

    GradleRunner gradleRunner(String gradleVersion, String... tasks) {
        GradleRunner.create()
                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
                .withProjectDir(projectDir)
                .withArguments((tasks + '--full-stacktrace').toList())
                .withTestKitDir(tempDir)
                .withPluginClasspath()
                .forwardOutput()
                .tap {
                    if (gradleVersion != null) {
                        withGradleVersion(gradleVersion)
                    }
                }
    }

    static private List<String> gradleVersionsUnderTest() {
        def explicitGradleVersions = System.getProperty('org.gradle.test.gradleVersions')
        if (explicitGradleVersions) {
            return Arrays.asList(explicitGradleVersions.split("\\|"))
        } else {
            [GradleVersion.current().version]
        }
    }

    String rewriteYamlText = """\
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
            """.stripIndent()

    String buildGradleFileText = """\
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
                configFile = "rewrite-config.yml"
                activeRecipe("org.openrewrite.gradle.SayHello", "org.openrewrite.java.format.AutoFormat")
            }
            """.stripIndent()

    String helloWorldJavaBeforeRefactor = """\
            package org.openrewrite.before;
            
            public class HelloWorld { public static void sayGoodbye() {System.out.println("Hello world");
                }public static void main(String[] args) {   sayGoodbye(); }
            }
            """.stripIndent()

    String helloWorldJavaAfterRefactor = """\
            package org.openrewrite.after;
            
            public class HelloWorld {
                public static void sayHello() {
                    System.out.println("Hello world");
                }
            
                public static void main(String[] args) {
                    sayHello();
                }
            }
            """.stripIndent()

}
