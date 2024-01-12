package org.openrewrite.gradle.isolated

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.api.BaseVariant
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.openrewrite.SourceFile
import org.openrewrite.java.JavaParser
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

class AndroidParser {

    private val BaseExtension.variants: DomainObjectSet<out BaseVariant>?
        get() = when (this) {
            is AppExtension -> applicationVariants
            is LibraryExtension -> libraryVariants
            is TestExtension -> applicationVariants
            else -> null
        }

    fun parseAndroidSources(project: Project, alreadyParsed: MutableSet<Path>): List<Path> {
        val baseExtension: AppExtension = project.extensions.findByType(AppExtension::class.java) ?: return emptyList()

        val sourceDirectories = baseExtension.applicationVariants
            .flatMap { variant ->
                variant.sourceSets.flatMap { it.kotlinDirectories + it.javaDirectories }
            }

        val unparsedSources: List<Path> =
            sourceDirectories.filter { it.exists() && !alreadyParsed.contains(it.toPath()) }
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

        return unparsedSources
    }

    fun getJavaSources(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path> {
        return unparsedSources
            .filter { it.toString().endsWith(".java") && !alreadyParsed.contains(it) }
    }

    fun getKotlinSrouces(unparsedSources: List<Path>, alreadyParsed: MutableSet<Path>): List<Path>  {
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