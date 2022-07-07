import nl.javadude.gradle.plugins.license.LicenseExtension
import org.gradle.rewrite.build.GradleVersionData
import org.gradle.rewrite.build.GradleVersionsCommandLineArgumentProvider
import java.util.*

plugins {
    java
    kotlin("jvm") version("1.6.20")
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

val gradleProvided = configurations.create("gradleProvided")
configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        if(name.startsWith("test")) {
            eachDependency {
                if(requested.name == "groovy-xml") {
                    useVersion("3.0.9")
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
    .apply {
        resolutionStrategy {
            cacheChangingModulesFor(0, TimeUnit.SECONDS)
            cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        }
    }
    .resolvedConfiguration.firstLevelModuleDependencies.iterator().next().moduleVersion

val rewriteConfName = "rewriteDependencies"
val rewriteDependencies = configurations.create("rewriteDependencies")

configurations.getByName("compileOnly").extendsFrom(plugin)

dependencies {
    "rewriteDependencies"("org.openrewrite:rewrite-core:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-hcl:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java-11:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-java-8:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-json:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-xml:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-yaml:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-properties:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-protobuf:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-groovy:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-gradle:$rewriteVersion")
    "rewriteDependencies"("org.openrewrite:rewrite-maven:$rewriteVersion")
    // Newer versions of checkstyle are compiled with a newer version of Java than is supported with gradle 4.x
    "rewriteDependencies"("com.puppycrawl.tools:checkstyle:9.3") {
        isTransitive = false
    }
    "rewriteDependencies"("com.fasterxml.jackson.module:jackson-module-kotlin:latest.release")
    "rewriteDependencies"(platform("org.jetbrains.kotlin:kotlin-bom:1.6.21"))

    implementation("org.openrewrite:rewrite-core:$rewriteVersion") {
        isTransitive = false
    }
    compileOnly("org.openrewrite:rewrite-hcl:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-java:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-java-11:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-java-8:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-json:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-xml:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-yaml:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-properties:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-protobuf:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-groovy:$rewriteVersion")
    compileOnly("org.openrewrite:rewrite-gradle:$rewriteVersion")
    compileOnly("com.puppycrawl.tools:checkstyle:9.3")

    "gradleProvided"(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testImplementation("org.assertj:assertj-core:latest.release")
    testImplementation("org.openrewrite:rewrite-gradle:$rewriteVersion")
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(plugin)
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishPlugins"))

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
val versionManifest = tasks.register("writeVersionManifest") {
    group = "other"
    description = "Produce a text file containing the version number of this plugin to be used when invoking the plugin from tests"

    val outputFile = project.file("src/test/resources/plugin-version.txt")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.file(outputFile)
    doLast {
        outputFile.parentFile.mkdirs()
        outputFile.writeText(version)
    }
}
val testManifest = tasks.register("writeTestManifest") {
    group = "other"
    description = "Produce a text file containing the classpath to be used when invoking the plugin from tests"

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val outputFile = project.file("src/test/resources/plugin-classpath.txt")
    dependsOn(runtimeClasspath)
    dependsOn(gradleProvided)

    outputs.file(outputFile)

    doLast {
        runtimeClasspath.get().resolve().minus(gradleProvided.resolve())

        val contents = sequenceOf(tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
            .plus(runtimeClasspath.get().resolve()
                .minus(gradleProvided.resolve())
                .map(File::getAbsolutePath))
            .joinToString("\n")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(contents)
    }
}

tasks.named("processTestResources") {
    dependsOn(testManifest, versionManifest)
}

tasks.named<Test>("test") {
    systemProperty(
        "org.openrewrite.test.gradleVersion", project.findProperty("testedGradleVersion") ?: gradle.gradleVersion
    )
    val jar: Jar = tasks.named<Jar>("jar").get()
    dependsOn(jar, testManifest, versionManifest)
}

val testGradle4 = tasks.register<Test>("testGradle4") {
    systemProperty("org.openrewrite.test.gradleVersion", "4.0")
    // Gradle 4.0 predates support for Java 11
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    val jar: Jar = tasks.named<Jar>("jar").get()
    dependsOn(jar, testManifest, versionManifest)
    jvmArgs("-DjarLocationForTest=${jar.archiveFile.get().asFile.absolutePath}")
}
tasks.named("check").configure {
    dependsOn(testGradle4)
}

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
    exclude("**/versions.properties")
    exclude("**/*.txt")
}

// This is here to silence a warning from Gradle about tasks using each-others outputs without declaring a dependency
tasks.named("licenseMain") {
    dependsOn(gVP)
}
tasks.named("licenseTest") {
    dependsOn(testManifest, versionManifest)
}
// The plugin that adds this task does it weirdly so it isn't available for configuration yet.
// So have this behavior applied to it whenever it _is_ added.
tasks.configureEach {
    if(name == "publishPluginJar") {
        dependsOn(gVP)
    }
}
