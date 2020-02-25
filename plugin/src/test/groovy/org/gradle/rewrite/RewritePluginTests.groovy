package org.gradle.rewrite


import spock.lang.Unroll

class RewritePluginTests extends AbstractRewritePluginTests {
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
    def "fixes source lint (gradle version #gradleVersion)"() {
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
}
