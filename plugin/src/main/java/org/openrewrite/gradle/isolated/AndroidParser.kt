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
import org.openrewrite.gradle.RewriteExtension
import org.openrewrite.java.internal.JavaTypeCache
import org.openrewrite.kotlin.KotlinParser
import org.openrewrite.style.NamedStyles
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.stream.Collectors
import java.util.stream.Stream

class AndroidParser(
    val rewriteExtension: RewriteExtension,
    val styles: List<NamedStyles>,
    val baseDir: Path,
) {

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

    private fun getKotlinParser(javaTypeCache: JavaTypeCache): KotlinParser {
        return KotlinParser.builder()
            .styles(styles)
            .typeCache(javaTypeCache)
            .logCompilationWarningsAndErrors(rewriteExtension.getLogCompilationWarningsAndErrors())
            .build()
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

        val variants = subproject.extensions.findByType(BaseExtension::class.java)?.variants ?: return EMPTY_STREAM

        val sources = variants.map { variant ->
            variant.sourceSets.flatMap { it.kotlinDirectories + it.javaDirectories }
        }
        val javaTypeCache = JavaTypeCache()

        var streamToReturn = Stream.empty<SourceFile>()

        var parsedCount = 0

        sources.forEach { sourceDirectories ->
            val allSourceFiles = getAllSourceFiles(sourceDirectories, alreadyParsed)

            // process java
//            val allJavaFiles = getJavaSources(allSourceFiles, alreadyParsed)
//            val (parsedJavaFiles, javaCount) = parseJavaFiles(allJavaFiles, javaTypeCache, exclusions, ctx)
//            parsedCount += javaCount
//            streamToReturn = Stream.concat(streamToReturn, parsedJavaFiles)

            // process kotlin
            val allKotlinFiles = getKotlinSrouces(allSourceFiles, alreadyParsed)
            alreadyParsed.addAll(allKotlinFiles)
            val (parsedKotlinFiles, kotlinCount) = parseKotlinFiles(allKotlinFiles, javaTypeCache, exclusions, ctx)
            parsedCount += kotlinCount
            streamToReturn = Stream.concat(streamToReturn, parsedKotlinFiles)
        }

        return streamToReturn to parsedCount
    }

//    private fun parseJavaFiles(
//        javaFiles: List<Path>,
//        javaTypeCache: JavaTypeCache,
//        exclusions: Collection<PathMatcher>,
//        ctx: ExecutionContext
//    ): Pair<Stream<SourceFile>, Int> {
//
//    }

    private fun parseKotlinFiles(
        kotlinFiles: List<Path>,
        javaTypeCache: JavaTypeCache,
        exclusions: Collection<PathMatcher>,
        ctx: ExecutionContext
    ): Pair<Stream<SourceFile?>, Int> {
        val stream = Stream.of(getKotlinParser(javaTypeCache))
            .flatMap { kp -> kp.parse(kotlinFiles, baseDir, ctx) }
            .map {
                if (isExcluded(exclusions, it.sourcePath)) {
                    null
                } else {
                    it
                }
            }
            .filter { it != null }

        return stream to kotlinFiles.size
//            .map { it -> it.withMarkers(it.getMarkers().add(javaVersion)) }

    }

    // copied over
    private fun isExcluded(exclusions: Collection<PathMatcher>, path: Path): Boolean {
        for (excluded in exclusions) {
            if (excluded.matches(path)) {
                return true
            }
        }
        // PathMather will not evaluate the path "build.gradle" to be matched by the pattern "**/build.gradle"
        // This is counter-intuitive for most users and would otherwise require separate exclusions for files at the root and files in subdirectories
        return if (!path.isAbsolute && !path.startsWith(File.separator)) {
            isExcluded(exclusions, Paths.get("/$path"))
        } else false
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


    fun getJavaSources(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path> {
        return unparsedSources
            .filter { it.toString().endsWith(".java") && !alreadyParsed.contains(it) }
    }

    fun getKotlinSrouces(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path> {
        return unparsedSources
            .filter { it.toString().endsWith(".kt") && !alreadyParsed.contains(it) }
    }
}