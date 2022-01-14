import nl.javadude.gradle.plugins.license.LicenseExtension
import org.gradle.rewrite.build.GradleVersionData
import org.gradle.rewrite.build.GradleVersionsCommandLineArgumentProvider
import java.util.*

plugins {
    java
    groovy
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version ("0.15.0")
    id("com.github.hierynomus.license") version "0.16.1" apply false
    `maven-publish`
}

apply(plugin = "license")

pluginBundle {
    website = "https://github.com/openrewrite/rewrite-gradle-plugin"
    vcsUrl = "https://github.com/openrewrite/rewrite-gradle-plugin.git"
    tags = listOf("rewrite", "refactoring", "java", "checkstyle")
}

gradlePlugin {
    plugins {
        create("rewriteMetrics") {
            id = "org.openrewrite.rewrite-metrics"
            displayName = "Rewrite metrics publishing"
            description = "Publish metrics about refactoring operations happening across your organization."
            implementationClass = "org.openrewrite.gradle.RewriteMetricsPlugin"
        }

        create("rewrite") {
            id = "org.openrewrite.rewrite"
            displayName = "Rewrite"
            description = "Automatically eliminate technical debt"
            implementationClass = "org.openrewrite.gradle.RewritePlugin"
        }
    }
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    mavenCentral()
}

configurations.all {
    val isTestConfiguration = name.startsWith("test")
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        if(isTestConfiguration) {
            eachDependency {
                if (requested.group == "org.codehaus.groovy" && requested.version == "3.0.9") {
                    useVersion("3.0.8")
                    because("spock-bom:2.0-groovy-3.0 requires 3.0.8, rewrite-groovy pulls in 3.0.9. " +
                        "Using groovy to run this project's tests is a headache and we should migrate them to Java.")
                }
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named<JavaCompile>("compileJava") {
    options.isFork = true
    options.forkOptions.executable = "javac"
    options.compilerArgs.addAll(listOf("--release", "8"))
}

val plugin: Configuration by configurations.creating

// Resolving dependency configurations during the configuration phase is a Gradle performance anti-pattern
// But I don't know a better way than this to keep com.gradle.plugin-publish from publishing the requested version rather than the resolved version
val latest = if(project.hasProperty("releasing")) {
    "latest.release"
} else {
    "latest.integration"
}
val rewriteVersion = configurations.detachedConfiguration(dependencies.create("org.openrewrite:rewrite-core:$latest"))
    .resolvedConfiguration.firstLevelModuleDependencies.iterator().next().moduleVersion
val checkstyleVersion = configurations.detachedConfiguration(dependencies.create("com.puppycrawl.tools:checkstyle:latest.release"))
    .resolvedConfiguration.firstLevelModuleDependencies.iterator().next().moduleVersion

val rewriteConfName = "rewriteDependencies"
val rewriteDependencies = configurations.create("rewriteDependencies")

configurations.getByName("compileOnly").extendsFrom(plugin)

// Fixed version numbers because com.gradle.plugin-publish will publish poms with requested rather than resolved versions
val prometheusVersion = "1.3.0"
val nettyVersion = "1.1.0"

dependencies {
    plugin("io.micrometer.prometheus:prometheus-rsocket-client:$prometheusVersion")
    plugin("io.rsocket:rsocket-transport-netty:$nettyVersion")

    api("io.micrometer.prometheus:prometheus-rsocket-client:$prometheusVersion")
    api("io.rsocket:rsocket-transport-netty:$nettyVersion")

    "rewriteDependencies"("org.openrewrite:rewrite-core:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-hcl:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java-11:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java-8:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-json:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-xml:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-yaml:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-properties:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-groovy:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-gradle:$rewriteVersion")
    "rewriteDependencies"("com.puppycrawl.tools:checkstyle:$checkstyleVersion")
    implementation("org.openrewrite:rewrite-core:$rewriteVersion")
    implementation("org.openrewrite:rewrite-hcl:$rewriteVersion")
    implementation("org.openrewrite:rewrite-java:$rewriteVersion")
    implementation("org.openrewrite:rewrite-java-11:$rewriteVersion")
    implementation("org.openrewrite:rewrite-java-8:$rewriteVersion")
    implementation("org.openrewrite:rewrite-json:$rewriteVersion")
    implementation("org.openrewrite:rewrite-xml:$rewriteVersion")
    implementation("org.openrewrite:rewrite-yaml:$rewriteVersion")
    implementation("org.openrewrite:rewrite-properties:$rewriteVersion")
    implementation("org.openrewrite:rewrite-groovy:$rewriteVersion")
    implementation("org.openrewrite:rewrite-gradle:$rewriteVersion")
    implementation("com.puppycrawl.tools:checkstyle:$checkstyleVersion")

    testImplementation(gradleTestKit())
    testImplementation(localGroovy())
    testImplementation(platform("org.spockframework:spock-bom:2.0-groovy-3.0"))
    testImplementation("org.spockframework:spock-core")
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(plugin)
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishPlugins"))

tasks.named<Test>("test") {
    systemProperty(
        GradleVersionsCommandLineArgumentProvider.PROPERTY_NAME,
        project.findProperty("testedGradleVersion") ?: gradle.gradleVersion
    )
}

val testGradle4 = tasks.register<Test>("testGradle4") {
    systemProperty(GradleVersionsCommandLineArgumentProvider.PROPERTY_NAME, "4.0")
    // Gradle 4.0 predates support for Java 11
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    dependsOn(tasks.named("jar"))
    val jar: Jar = tasks.named<Jar>("jar").get()
    jvmArgs("-DjarLocationForTest=${jar.archiveFile.get().asFile.absolutePath}")
}
tasks.named("check").configure {
    dependsOn(testGradle4)
}

tasks.register<Test>("testGradleReleases") {
    jvmArgumentProviders.add(GradleVersionsCommandLineArgumentProvider(GradleVersionData::getReleasedVersions))
}

tasks.register<Test>("testGradleNightlies") {
    jvmArgumentProviders.add(GradleVersionsCommandLineArgumentProvider(GradleVersionData::getNightlyVersions))
}

tasks.withType<Test>() {
    useJUnitPlatform()
}

val gVP = tasks.register("generateVersionsProperties") {

    val outputFile = file("src/main/resources/versions.properties")
    description = "Creates a versions.properties in $outputFile"
    group = "Build"

    inputs.files(rewriteDependencies)
    inputs.property("releasing", project.hasProperty("releasing"))

    outputs.file(outputFile)

    doLast {
        if(outputFile.exists()) {
            outputFile.delete()
        } else {
            outputFile.parentFile.mkdirs()
        }
        val resolvedModules = rewriteDependencies.resolvedConfiguration.firstLevelModuleDependencies
        val props = Properties()
        for(module in resolvedModules) {
            props["${module.moduleGroup}:${module.moduleName}"] = module.moduleVersion
        }
        outputFile.outputStream().use {
            props.store(it, null)
        }
    }
}

tasks.named("processResources") {
    dependsOn(gVP)
}

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
    exclude("**/versions.properties")
}

// This is here to silence a warning from Gradle about tasks using each-others outputs without declaring a dependency
tasks.named("licenseMain") {
    dependsOn(gVP)
}
// The plugin that adds this task does it weirdly so it isn't available for configuration yet.
// So have this behavior applied to it whenever it _is_ added.
tasks.configureEach {
    if(name == "publishPluginJar") {
        dependsOn(gVP)
    }
}
