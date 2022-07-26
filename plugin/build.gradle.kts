import nl.javadude.gradle.plugins.license.LicenseExtension
import org.gradle.rewrite.build.GradleVersionData
import org.gradle.rewrite.build.GradleVersionsCommandLineArgumentProvider
import java.util.*

plugins {
    kotlin("jvm") version("1.6.20")
    id("com.gradle.plugin-publish") version "1.0.0"
    id("com.github.hierynomus.license") version "0.16.1"
}

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
    if (!project.hasProperty("releasing")) {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
    }

    mavenCentral()
}

val latest = if (project.hasProperty("releasing")) {
    "latest.release"
} else {
    "latest.integration"
}

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

val rewriteDependencies = configurations.create("rewriteDependencies")

dependencies {
    "rewriteDependencies"(platform("org.openrewrite:rewrite-bom:$latest"))
    "rewriteDependencies"("org.openrewrite:rewrite-core")
    "rewriteDependencies"("org.openrewrite:rewrite-hcl")
    "rewriteDependencies"("org.openrewrite:rewrite-java")
    "rewriteDependencies"("org.openrewrite:rewrite-java-11")
    "rewriteDependencies"("org.openrewrite:rewrite-java-8")
    "rewriteDependencies"("org.openrewrite:rewrite-json")
    "rewriteDependencies"("org.openrewrite:rewrite-xml")
    "rewriteDependencies"("org.openrewrite:rewrite-yaml")
    "rewriteDependencies"("org.openrewrite:rewrite-properties")
    "rewriteDependencies"("org.openrewrite:rewrite-protobuf")
    "rewriteDependencies"("org.openrewrite:rewrite-groovy")
    "rewriteDependencies"("org.openrewrite:rewrite-gradle")
    "rewriteDependencies"("org.openrewrite:rewrite-maven")
    // Newer versions of checkstyle are compiled with a newer version of Java than is supported with gradle 4.x
    "rewriteDependencies"("com.puppycrawl.tools:checkstyle:9.3")
    "rewriteDependencies"("com.fasterxml.jackson.module:jackson-module-kotlin:latest.release")

    implementation(platform("org.openrewrite:rewrite-bom:$latest"))
    compileOnly("org.openrewrite:rewrite-core")
    compileOnly("org.openrewrite:rewrite-gradle")
    compileOnly("org.openrewrite:rewrite-groovy")
    compileOnly("org.openrewrite:rewrite-hcl")
    compileOnly("org.openrewrite:rewrite-java")
    compileOnly("org.openrewrite:rewrite-properties")
    compileOnly("org.openrewrite:rewrite-protobuf")
    compileOnly("org.openrewrite:rewrite-json")
    compileOnly("org.openrewrite:rewrite-xml")
    compileOnly("org.openrewrite:rewrite-yaml")
    compileOnly("com.puppycrawl.tools:checkstyle:9.3")

    testImplementation(platform("org.junit:junit-bom:latest.release"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.assertj:assertj-core:latest.release")
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
    val outputFile = file("$buildDir/rewrite/versions.properties")
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

tasks.named<Copy>("processResources") {
    into("/") {
        from(gVP)
    }
}

tasks.named<Test>("test") {
    systemProperty(
        "org.openrewrite.test.gradleVersion", project.findProperty("testedGradleVersion") ?: gradle.gradleVersion
    )
}

val testGradle4 = tasks.register<Test>("testGradle4") {
    systemProperty("org.openrewrite.test.gradleVersion", "4.0")
    systemProperty("jarLocationForTest", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    // Gradle 4.0 predates support for Java 11
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
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
