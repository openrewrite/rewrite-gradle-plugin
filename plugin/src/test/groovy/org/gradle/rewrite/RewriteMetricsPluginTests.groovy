package org.gradle.rewrite

import io.netty.buffer.ByteBufUtil
import io.rsocket.AbstractRSocket
import io.rsocket.RSocket
import io.rsocket.RSocketFactory
import io.rsocket.frame.decoder.PayloadDecoder
import io.rsocket.transport.netty.server.TcpServerTransport
import io.rsocket.util.DefaultPayload
import reactor.core.publisher.Mono
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RewriteMetricsPluginTests extends AbstractRewritePluginTests {
    def setup() {
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
        settingsFile << """
            rootProject.name = 'hello-world'
            include 'a', 'b'    
        """

        projectDir.newFolder('b')

        File aSourceFolder = new File(projectDir.root, 'a/src/main/java')
        aSourceFolder.mkdirs()
        new File(aSourceFolder, 'A.java') << 'public class A {}'

        buildFile = projectDir.newFile('build.gradle')
        buildFile << """
            plugins {
                id 'org.gradle.rewrite-metrics'
                id 'org.gradle.rewrite-checkstyle' apply false
            }
            
            rewriteMetrics {
                metricsUri = URI.create('tcp://localhost:7102')
            }
            
            subprojects {
                apply plugin: 'java'
                apply plugin: 'checkstyle'
                apply plugin: 'org.gradle.rewrite-checkstyle'
            
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
            }
        """
    }

    @Unroll
    def "metrics published (gradle version #gradleVersion)"() {
        given:
        def acceptLatch = new CountDownLatch(1)
        def scrapeResponseLatch = new CountDownLatch(1)

        def serverSocket = new AtomicReference<RSocket>()

        RSocketFactory.receive()
                .frameDecoder(PayloadDecoder.ZERO_COPY)
                .acceptor { setup, sendingSocket ->
                    serverSocket.set(sendingSocket)
                    acceptLatch.countDown()
                    return Mono.just(new AbstractRSocket() {
                    })
                }
                .transport(TcpServerTransport.create(7102))
                .start()
                .subscribe()

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        acceptLatch.await(30, TimeUnit.SECONDS)

        when:
        serverSocket.get().requestResponse(DefaultPayload.create("plaintext"))
                .map { payload ->
                    def scrape = new String(ByteBufUtil.getBytes(payload.sliceData()), StandardCharsets.UTF_8)
                    println(scrape)
                    scrapeResponseLatch.countDown()
                }
                .onErrorStop()
                .subscribe()

        then:
        scrapeResponseLatch.await(30, TimeUnit.SECONDS)

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }
}
