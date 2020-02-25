package org.gradle.rewrite

import spock.lang.Unroll

class RewriteSpringTests extends AbstractRewritePluginTests {

    @Unroll
    def "convert Spring XML configuration to annotation configuration (gradle version #gradleVersion)"() {
        given:
        projectDir.newFolder('src', 'main', 'resources')
        projectDir.newFile('src/main/resources/application.xml') << """\
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                            http://www.springframework.org/schema/beans/spring-beans-3.0.xsd">
                <bean id="userRepository" class="repositories.UserRepository"/>
            </beans>
        """.stripIndent()

        settingsFile = projectDir.newFile('settings.gradle')
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile = projectDir.newFile('build.gradle')
        buildFile << """
            plugins {
                id 'java'
                id 'org.gradle.rewrite'
            }
            
            repositories {
                mavenLocal()
                mavenCentral()
            }
            
            dependencies {
                implementation 'org.springframework:spring-beans:5.2.3.RELEASE'
            }
        """

        projectDir.newFolder('src', 'main', 'java', 'repositories')

        def sourceFile = writeSource """
            package repositories;
            
            public class UserRepository {
            }
        """.stripIndent()

        when:
        gradleRunner(gradleVersion as String).build()

        then:
        sourceFile.text == """
            package repositories;
            
            import org.springframework.stereotype.Component;
            
            @Component
            public class UserRepository {
            }
        """.stripIndent()

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }
}
