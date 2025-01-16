package org.openrewrite.gradle


import io.rsocket.Payload
import io.rsocket.RSocket
import io.rsocket.core.RSocketServer
import io.rsocket.frame.decoder.PayloadDecoder
import io.rsocket.transport.netty.server.TcpServerTransport
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import spock.lang.Ignore
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RewriteMetricsPluginTests
//        extends RewriteTestBase
{

    File settingsFile

    def setup() {
        Hooks.onOperatorDebug()

        projectDir.newFolder('config', 'checkstyle')
        projectDir.newFile('config/checkstyle/checkstyle.xml') << """\
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                    "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <module name="TreeWalker">
                    <module name="SimplifyBooleanReturn"/>
                </module>
            </module>
        """.stripIndent()

        settingsFile = projectDir.newFile('settings.gradle')
        settingsFile << """\
            rootProject.name = 'hello-world'
            include 'a', 'b'
        """.stripIndent()

        projectDir.newFolder('b')

        File aSourceFolder = new File(projectDir.root, 'a/src/main/java')
        aSourceFolder.mkdirs()
        new File(aSourceFolder, 'A.java') << 'public class A {}'

        buildFile = projectDir.newFile('build.gradle')
        buildFile << """\
            plugins {
                id 'org.openrewrite.rewrite-metrics'
                id 'org.openrewrite.rewrite-checkstyle' apply false
            }

            rewriteMetrics {
                metricsUri = URI.create('tcp://localhost:7102')
            }

            subprojects {
                apply plugin: 'java'
                apply plugin: 'checkstyle'
                apply plugin: 'org.openrewrite.rewrite-checkstyle'

                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
        """.stripIndent()
    }

    @Ignore("ByteBuf leaking while trying to send dying push?")
    @Unroll
    def "metrics published (gradle version #gradleVersion)"() {
        given:
        def scrapeResponseLatch = new CountDownLatch(1)

        RSocketServer.create()
                .payloadDecoder(PayloadDecoder.ZERO_COPY)
                .acceptor { setup, sendingSocket ->
                    return new RSocket() {
                        @Override
                        Mono<Void> fireAndForget(Payload payload) {
                            try {
                                scrapeResponseLatch.countDown()
                                return Mono.empty()
                            } finally {
                                payload.release()
                            }
                        }
                    }
                }
                .bind(TcpServerTransport.create(7102))
                .doOnError { t ->
                    t.printStackTrace()
                }
                .subscribe()

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        scrapeResponseLatch.await(30, TimeUnit.SECONDS)

        where:
        gradleVersion << org.openrewrite.gradle.RewriteTestBase.GRADLE_VERSIONS_UNDER_TEST
    }
}
