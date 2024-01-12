package org.openrewrite.gradle.isolated

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.openrewrite.ExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.java.JavaParser
import org.openrewrite.polyglot.SourceFileStream
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

class AndroidParser {

    companion object {
        private val EMPTY_STREAM: Pair<Stream<SourceFile>, Int> = Stream.empty<SourceFile>() to 0
    }

    private val BaseExtension.variants: DomainObjectSet<out BaseVariant>?
        get() = when (this) {
            is AppExtension -> applicationVariants
            is LibraryExtension -> libraryVariants
            is TestExtension -> applicationVariants
            else -> null
        }

    fun isAndroidProject(subproject: Project): Boolean {
        return subproject.extensions.findByType(BaseExtension::class.java) != null
    }

    fun parseAndroidSources(
        subproject: Project,
        exclusions: Collection<PathMatcher>,
        alreadyParsed: MutableSet<Path>,
        ctx: ExecutionContext
    ): Pair<Stream<SourceFile>, Int> {

        val variants = subproject.extensions.getByType(BaseExtension::class.java).variants ?: return EMPTY_STREAM

        val sources = variants.map { variant ->
            variant.sourceSets.flatMap { it.kotlinDirectories + it.javaDirectories }
        }

        var streamToReturn = Stream.empty<SourceFile>()

        sources.forEach { sourceDirectories ->
            var parsedCount = 0
            val allSourceFiles = getAllSourceFiles(sourceDirectories, alreadyParsed)

            // process java
            val allJavaFiles = getJavaSources(allSourceFiles, alreadyParsed)
            val (parsedJavaFiles, javaCount) = parseJavaFiles(allJavaFiles)
            parsedCount += javaCount
            streamToReturn = Stream.concat(streamToReturn, parsedJavaFiles)

            // process kotlin
            val allKotlinFiles = getKotlinSrouces(allSourceFiles, alreadyParsed)
            val (parsedKotlinFiles, kotlinCount) = parseKotlinFiles(allSourceFiles)
            parsedCount += kotlinCount
            streamToReturn = Stream.concat(streamToReturn, parsedKotlinFiles)

        }

        return EMPTY_STREAM
    }

    private fun parseJavaFiles(javaFiles: List<Path>): Pair<Stream<SourceFile>, Int> {

    }

    private fun parseKotlinFiles(kotlinFiles: List<Path>): Pair<Stream<SourceFile>, Int> {

    }

    private fun getAllSourceFiles(sourceDirectories: List<File>, alreadyParsed: MutableSet<Path>): List<Path> {
        return sourceDirectories.filter { it.exists() && !alreadyParsed.contains(it.toPath()) }
            .stream()
            .flatMap { sourceDir ->
                return@flatMap try {
                    Files.walk(sourceDir.toPath())
                } catch (e: IOException) {
                    throw UncheckedIOException(e)
                }
            }
            .filter(Files::isRegularFile)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .collect(Collectors.toList())
    }

    fun parseAndroidSources(project: Project, alreadyParsed: MutableSet<Path>): List<AndroidSourceSet> {
        val baseExtension: BaseExtension =
            project.extensions.findByType(BaseExtension::class.java) ?: return emptyList()

        return baseExtension.variants.map { it.sourceSets.forEach { it. } }
//
//        val sourceDirectories = baseExtension.applicationVariants
//            .flatMap { variant ->
//                variant.sourceSets.flatMap { it.kotlinDirectories + it.javaDirectories }
//            }
//
//        val unparsedSources: List<Path> =
//            sourceDirectories.filter { it.exists() && !alreadyParsed.contains(it.toPath()) }
//                .stream()
//                .flatMap { sourceDir ->
//                    return@flatMap try {
//                        Files.walk(sourceDir.toPath())
//                    } catch (e: IOException) {
//                        throw UncheckedIOException(e)
//                    }
//                }
//                .filter(Files::isRegularFile)
//                .map(Path::toAbsolutePath)
//                .map(Path::normalize)
//                .distinct()
//                .collect(Collectors.toList())
//
//        return unparsedSources
    }

    fun getJavaSources(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path> {
        return unparsedSources
            .filter { it.toString().endsWith(".java") && !alreadyParsed.contains(it) }
    }

    fun getKotlinSrouces(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path> {
        return unparsedSources
            .filter { it.toString().endsWith(".kt") && !alreadyParsed.contains(it) }
    }


//
//        return unparsedSources
//
//        val javaPaths = unparsedSources
//            .filter { it.toString().endsWith(".java") && !alreadyParsed.contains(it) }
//
//        val kotlinPaths = unparsedSources
//            .filter { it.toString().endsWith(".kt") && !alreadyParsed.contains(it) }


}