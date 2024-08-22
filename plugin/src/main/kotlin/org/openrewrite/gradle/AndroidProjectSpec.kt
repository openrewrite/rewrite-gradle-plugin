/*
 * Copyright 2024 the original author or authors.
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

import org.intellij.lang.annotations.Language
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Utility to help with writing android projects to disk to assist with plugin testing
 */
class AndroidProjectSpec(
    private val dir: Path
) {
    private val subprojects: MutableList<AndroidProjectSpec> = mutableListOf()
    private val sourceSets: MutableList<AndroidSourceSetSpec> = mutableListOf()

    @Language("groovy")
    var groovyBuildScript: String? = null

    fun buildGradle(@Language("groovy") text: String) {
        groovyBuildScript = text.trimIndent()
    }

    @Language("groovy")
    var settingsGradle: String? = null
    fun settingsGradle(@Language("groovy") text: String) {
        settingsGradle = text.trimIndent()
    }

    @Language("yaml")
    var rewriteYaml: String? = null
    fun rewriteYaml(@Language("yaml") text: String) {
        rewriteYaml = text.trimIndent()
    }

    @Language("xml")
    var checkstyleXml: String? = null
    fun checkstyleXml(@Language("xml") text: String) {
        checkstyleXml = text.trimIndent()
    }

    private val propertiesFiles: MutableMap<String, String> = mutableMapOf()
    fun propertiesFile(name: String, @Language("properties") text: String) {
        propertiesFiles[name] = text
    }

    private val textFiles: MutableMap<String, String> = mutableMapOf()
    fun textFile(name: String, text: String) {
        textFiles[name] = text
    }

    fun subproject(name: String, init: AndroidProjectSpec.() -> Unit): AndroidProjectSpec {
        val subproject = AndroidProjectSpec(dir.resolve(name)).apply(init)
        subprojects.add(subproject)
        return subproject
    }

    fun sourceSet(
        name: String,
        sourceCharset: Charset = StandardCharsets.UTF_8,
        resourceCharset: Charset = StandardCharsets.UTF_8,
        init: AndroidSourceSetSpec.() -> Unit
    ): AndroidSourceSetSpec {
        val sourceSet = AndroidSourceSetSpec(name, sourceCharset, resourceCharset).apply(init)
        sourceSets.add(sourceSet)
        return sourceSet
    }

    fun build(): AndroidProjectSpec {
        Files.createDirectories(dir)
        val settings = dir.resolve("settings.gradle")
        val lines = ArrayList<String>()
        if (settingsGradle == null) {
            lines.add("""
                 pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        google()
                        mavenLocal()
                        mavenCentral()
                    }
                }
                    
                dependencyResolutionManagement {
                    repositories {
                        gradlePluginPortal()
                        google()
                        mavenLocal()
                        mavenCentral()
                        maven {
                            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                        }
                    }
                }
            """.trimIndent())
            lines.add("rootProject.name = \"${dir.fileName}\"\n")
            if (!subprojects.isEmpty()) {
                val subprojectsDeclarations =
                    subprojects.joinToString("\n") { subproject -> "include('${subproject.dir.fileName}')" }
                lines.add(subprojectsDeclarations)
            }
            Files.write(settings, lines)
        } else {
            lines.add(settingsGradle!!)
        }
        Files.write(settings, lines)

        if (groovyBuildScript != null) {
            Files.write(dir.resolve("build.gradle"), groovyBuildScript!!.toByteArray())
        }

        if (rewriteYaml != null) {
            Files.write(dir.resolve("rewrite.yml"), rewriteYaml!!.toByteArray())
        }

        if (checkstyleXml != null) {
            dir.resolve("config/checkstyle/checkstyle.xml").apply {
                Files.createDirectories(parent)
                Files.write(dir, checkstyleXml!!.toByteArray())
            }
        }

        for (props in propertiesFiles.entries) {
            dir.resolve(props.key).apply {
                Files.createDirectories(parent)
                Files.write(dir, props.value.toByteArray())
            }
        }

        for (text in textFiles.entries) {
            dir.resolve(text.key).apply {
                Files.createDirectories(parent)
                Files.write(dir, text.value.toByteArray())
            }
        }

        for (sourceSet in sourceSets) {
            sourceSet.build(dir.resolve("src"))
        }
        for (subproject in subprojects) {
            subproject.build()
        }
        return this
    }
}

class AndroidSourceSetSpec(
    private val name: String,
    private val sourceCharset: Charset = StandardCharsets.UTF_8,
    private val resourceCharset: Charset = StandardCharsets.UTF_8
) {
    private val javaSources: MutableList<String> = mutableListOf()
    fun java(@Language("java") source: String) {
        javaSources.add(source.trimIndent())
    }

    private val kotlinSources: MutableList<String> = mutableListOf()
    fun kotlin(@Language("kotlin") source: String) {
        kotlinSources.add(source.trimIndent())
    }

    private val propertiesFiles: MutableMap<String, String> = mutableMapOf()
    fun propertiesFile(name: String, @Language("properties") text: String) {
        propertiesFiles[name] = text
    }

    private val yamlFiles: MutableMap<String, String> = mutableMapOf()
    fun yamlFile(name: String, @Language("yaml") text: String) {
        yamlFiles[name] = text
    }

    private val groovyClasses: MutableList<String> = mutableListOf()
    fun groovyClass(@Language("groovy") source: String) {
        groovyClasses.add(source.trimIndent())
    }


    @Suppress("RegExpSimplifiable")
    fun build(dir: Path): AndroidSourceSetSpec {
        Files.createDirectories(dir)
        for (javaSource in javaSources) {
            val packageDecl = if (javaSource.startsWith("package")) {
                "package\\s+([a-zA-Z0-9.]+);".toRegex(RegexOption.MULTILINE)
                    .find(javaSource)!!
                    .groupValues[1]
            } else {
                ""
            }.replace(".", "/")
            val clazz = ".*(class|interface|enum)\\s+([a-zA-Z0-9-_]+)".toRegex(RegexOption.MULTILINE)
                .find(javaSource)!!.groupValues[2]
            val path = if (packageDecl.isEmpty()) {
                "$name/java/$clazz.java"
            } else {
                "$name/java/$packageDecl/$clazz.java"
            }
            dir.resolve(path).apply {
                Files.createDirectories(parent)
                Files.write(this, javaSource.toByteArray(sourceCharset))
            }
        }
        for (kotlinSource in kotlinSources) {
            val packageDecl = if (kotlinSource.startsWith("package")) {
                "package\\s+([a-zA-Z0-9.]+)".toRegex(RegexOption.MULTILINE)
                    .find(kotlinSource)!!
                    .groupValues[1]
            } else {
                ""
            }.replace(".", "/")
            val clazz = ".*(class|interface|enum)\\s+([a-zA-Z0-9-_]+)".toRegex(RegexOption.MULTILINE)
                .find(kotlinSource)!!.groupValues[2]
            val path = if (packageDecl.isEmpty()) {
                "$name/kotlin/$clazz.kt"
            } else {
                "$name/kotlin/$packageDecl/$clazz.kt"
            }
            dir.resolve(path).apply {
                Files.createDirectories(parent)
                Files.write(this, kotlinSource.toByteArray(sourceCharset))
            }
        }
        for (groovySource in groovyClasses) {
            val packageDecl = if (groovySource.startsWith("package")) {
                "package\\s+([a-zA-Z0-9.]+);?".toRegex(RegexOption.MULTILINE)
                    .find(groovySource)!!
                    .groupValues[1]
            } else {
                ""
            }.replace(".", "/")
            val clazz = ".*(class|interface|enum)\\s+([a-zA-Z0-9-_]+)".toRegex(RegexOption.MULTILINE)
                .find(groovySource)!!.groupValues[2]
            val path = if (packageDecl.isEmpty()) {
                "$name/groovy/$clazz.groovy"
            } else {
                "$name/groovy/$packageDecl/$clazz.groovy"
            }
            dir.resolve(path).apply {
                Files.createDirectories(parent)
                Files.write(this, groovySource.toByteArray(sourceCharset))
            }
        }
        if (propertiesFiles.isNotEmpty()) {
            for (props in propertiesFiles.entries) {
                dir.resolve("$name/resources/${props.key}").apply {
                    Files.createDirectories(parent)
                    Files.write(this, props.value.toByteArray(resourceCharset))
                }
            }
        }
        if (yamlFiles.isNotEmpty()) {
            for (yaml in yamlFiles.entries) {
                dir.resolve("$name/resources/${yaml.key}").apply {
                    Files.createDirectories(parent)
                    Files.write(this, yaml.value.toByteArray(resourceCharset))
                }
            }
        }
        return this
    }
}

fun androidProject(dir: Path, init: AndroidProjectSpec.() -> Unit): AndroidProjectSpec {
    return AndroidProjectSpec(dir).apply(init).build()
}

/*fun commitFilesToGitRepo(dir: File) {
    exec("git init", dir)
    exec("git config user.email user@test.com", dir)
    exec("git config user.name TestUser", dir)
    exec("git add .", dir)
    exec("git commit -m \"Initial commit\"", dir)
}*/

private fun exec(command: String, workingDirectory: File) {
    Runtime.getRuntime().exec(command, null, workingDirectory)
}