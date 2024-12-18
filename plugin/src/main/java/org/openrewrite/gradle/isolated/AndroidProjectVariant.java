/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
 */
package org.openrewrite.gradle.isolated;

import com.android.build.gradle.api.BaseVariant;
import com.android.builder.model.SourceProvider;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

class AndroidProjectVariant {
    private static final Logger logger = Logging.getLogger(AndroidProjectVariant.class);
    private final String name;
    private final Map<String, Set<Path>> javaSourceSets;
    private final Map<String, Set<Path>> kotlinSourceSets;
    private final Map<String, Set<Path>> resourcesSourceSets;
    private final Set<String> sourceSetNames = new HashSet<>();
    private final Set<Path> compileClasspath;
    private final Set<Path> runtimeClasspath;

    AndroidProjectVariant(String name,
                          Map<String, Set<Path>> javaSourceSets,
                          Map<String, Set<Path>> kotlinSourceSets,
                          Map<String, Set<Path>> resourcesSourceSets,
                          Set<Path> compileClasspath,
                          Set<Path> runtimeClasspath) {
        this.name = name;
        this.javaSourceSets = javaSourceSets;
        this.kotlinSourceSets = kotlinSourceSets;
        this.resourcesSourceSets = resourcesSourceSets;
        this.compileClasspath = compileClasspath;
        this.runtimeClasspath = runtimeClasspath;

        sourceSetNames.addAll(javaSourceSets.keySet());
        sourceSetNames.addAll(kotlinSourceSets.keySet());
        sourceSetNames.addAll(this.resourcesSourceSets.keySet());
    }

    String getName() {
        return name;
    }

    Set<String> getSourceSetNames() {
        return sourceSetNames;
    }

    Set<Path> getJavaDirectories(String sourceSetName) {
        return javaSourceSets.computeIfAbsent(sourceSetName, key -> Collections.emptySet());
    }

    Set<Path> getKotlinDirectories(String sourceSetName) {
        return kotlinSourceSets.computeIfAbsent(sourceSetName, key -> Collections.emptySet());
    }

    Set<Path> getResourcesDirectories(String sourceSetName) {
        return resourcesSourceSets.computeIfAbsent(sourceSetName, key -> Collections.emptySet());
    }

    Set<Path> getCompileClasspath() {
        return compileClasspath;
    }

    Set<Path> getRuntimeClasspath() {
        return runtimeClasspath;
    }

    static AndroidProjectVariant fromBaseVariant(BaseVariant baseVariant) {
        Map<String, Set<Path>> javaSourceSets = new HashMap<>();
        Map<String, Set<Path>> kotlinSourceSets = new HashMap<>();
        Map<String, Set<Path>> resourceSourceSets = new HashMap<>();

        for (SourceProvider sourceProvider : baseVariant.getSourceSets()) {
            addSourceSets(javaSourceSets, sourceProvider.getName(), sourceProvider.getJavaDirectories());
            if (hasMethod(baseVariant, "getKotlinDirectories")) {
                // Android gradle plugin versions prior to 7 do not have BaseVariant#getKotlinDirectories
                addSourceSets(kotlinSourceSets, sourceProvider.getName(), sourceProvider.getKotlinDirectories());
            }
            addSourceSets(resourceSourceSets, sourceProvider.getName(), sourceProvider.getResDirectories());
            addSourceSets(resourceSourceSets, sourceProvider.getName(), sourceProvider.getResourcesDirectories());
        }

        Set<Path> compileClasspath = new LinkedHashSet<>();
        try {
            baseVariant.getCompileClasspath(null)
                    .getFiles()
                    .stream()
                    .map(File::toPath)
                    .forEach(compileClasspath::add);
        } catch (RuntimeException e) {
            // Calling BaseVariant#getCompileClasspath will throw an exception when run with
            // an AGP version less than 8.0 and a gradle version less than 8, when trying to
            // create a task using org.gradle.api.tasks.incremental.IncrementalTaskInputs which
            // was removed in gradle 8.
            logger.warn("Unable to determine compile class path", e);
        }

        Set<Path> runtimeClasspath = new LinkedHashSet<>();

        try {
            baseVariant.getRuntimeConfiguration().getFiles()
                    .stream()
                    .map(File::toPath)
                    .forEach(runtimeClasspath::add);
        } catch (Exception e) {
            logger.warn("Unable to determine runtime class path", e);
        }

        return new AndroidProjectVariant(
                baseVariant.getName(),
                javaSourceSets,
                kotlinSourceSets,
                resourceSourceSets,
                compileClasspath,
                runtimeClasspath);
    }

    private static void addSourceSets(Map<String, Set<Path>> sourceSets, String name, Collection<File> directories) {
        sourceSets.put(name, directories.stream().map(File::toPath).collect(Collectors.toSet()));
    }

    private static boolean hasMethod(BaseVariant baseVariant, String methodName, Class<?>... paramTypes) {
        try {
            baseVariant.getClass().getMethod(methodName, paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
