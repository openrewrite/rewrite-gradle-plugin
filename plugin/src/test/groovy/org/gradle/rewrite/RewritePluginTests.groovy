package org.gradle.rewrite

import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.management.ManagementFactory

class RewritePluginTests extends Specification {
    static final List<String> GRADLE_VERSIONS_UNDER_TEST = gradleVersionsUnderTest()

    @Rule
    TemporaryFolder projectDir = new TemporaryFolder()

    File settingsFile

    File buildFile

    def setup() {
        projectDir.newFolder('config', 'checkstyle')
        projectDir.newFile('config/checkstyle/checkstyle.xml') << """\
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                    "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <module name="TreeWalker">
                    <module name="SimplifyBooleanExpression"/>
                    <module name="SimplifyBooleanReturn"/>
                </module>
            </module>
        """.stripIndent()

        settingsFile = projectDir.newFile('settings.gradle')
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile = projectDir.newFile('build.gradle')
        buildFile << """
            plugins {
                id 'java'
                id 'checkstyle'
                id 'org.gradle.rewrite'
            }
            
            repositories {
                mavenLocal()
                mavenCentral()
            }
            
            dependencies {
                compileOnly 'org.gradle:rewrite-checkstyle:latest.integration'
            }
        """

        projectDir.newFolder('src', 'main', 'java', 'acme')
    }

    @Unroll
    def "fixes source lint  (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource """
            package acme;

            public class A {
                boolean ifNoElse() {
                    if (isOddMillis()) {
                        return true;
                    }
                    return false;
                }
                
                static boolean isOddMillis() {
                    boolean even = System.currentTimeMillis() % 2 == 0;
                    if (even == true) {
                        return false;
                    }
                    else {
                        return true;
                    }
                }
            }
        """.stripIndent()

        when:
        gradleRunner(gradleVersion as String).build()

        then:
        sourceFile.text == """
            package acme;

            public class A {
                boolean ifNoElse() {
                    return isOddMillis();
                }
                
                static boolean isOddMillis() {
                    boolean even = System.currentTimeMillis() % 2 == 0;
                    return !even;
                }
            }
        """.stripIndent()

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @SuppressWarnings("GroovyAssignabilityCheck")
    File writeSource(String source) {
        String packageName = (source =~ /package\s+([\w.]+)/)[0][1]
        String className = (source =~ /(class|interface)\s+(\w+)\s+/)[0][2]
        String sourceFilePackage = "src/main/java/${packageName.replace('.', '/')}"
        new File(projectDir.root, sourceFilePackage).mkdirs()
        def file = projectDir.newFile("$sourceFilePackage/${className}.java")
        file << source
        return file
    }

    GradleRunner gradleRunner(String gradleVersion) {
        GradleRunner.create()
                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
                .withProjectDir(projectDir.root)
                .withArguments('fixSourceLint', '-s')
                .withPluginClasspath()
                .forwardOutput()
                .tap {
                    gradleVersion == GradleVersion.current().toString() ? null : it.withGradleVersion(gradleVersion)
                }
    }

    static private List<String> gradleVersionsUnderTest() {
        def explicitGradleVersions = System.getProperty('org.gradle.test.gradleVersions')
        if (explicitGradleVersions) {
            return Arrays.asList(explicitGradleVersions.split("\\|"))
        } else {
            [GradleVersion.current().toString()]
        }
    }
}
