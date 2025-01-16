plugins {
    `groovy-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("rewriteMetrics") {
            id = "org.openrewrite.rewrite-metrics"
            displayName = "Rewrite metrics publishing"
            description = "Publish metrics about refactoring operations happening across your organization."
            implementationClass = "org.openrewrite.gradle.RewriteMetricsPlugin"
        }
    }
}

repositories {
    if (!project.hasProperty("releasing")) {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
    }

    mavenCentral()
}

dependencies {
    api("io.micrometer.prometheus:prometheus-rsocket-client:latest.release")
    api("io.rsocket:rsocket-transport-netty:latest.release")

    implementation(platform("io.netty:netty-bom:latest.release"))
    implementation("io.projectreactor.netty:reactor-netty-core:latest.release")
    implementation("io.projectreactor.netty:reactor-netty-http:latest.release")
    implementation("com.google.guava:guava:latest.release")

    runtimeOnly("org.xerial.snappy:snappy-java:latest.release")

    testImplementation(localGroovy())
    testImplementation("org.spockframework:spock-core:2.0-groovy-3.0") {
        exclude(group = "org.codehaus.groovy")
    }
}
