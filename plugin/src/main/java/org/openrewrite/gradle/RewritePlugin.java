/*
 * Copyright 2025 the original author or authors.
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
package org.openrewrite.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;
import org.openrewrite.gradle.dependencies.ResolvedDependencies;
import org.openrewrite.gradle.dependencies.ResolvedDependenciesProvider;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * When applied to the root project of a multi-project build, applies to all subprojects.
 * When applied to the root project the "rewrite" configuration and "rewrite" DSL created in the root project apply to
 * all parts of the build.
 * When applied to a subproject of a multi-project build, applies only to that subproject.
 * Creates "rewrite" dependency configuration and "rewrite" DSL in and applicable to only that subproject.
 */
@SuppressWarnings("unused")
public class RewritePlugin implements Plugin<Project> {

    @Nullable
    private ResolvedDependencies resolvedDependencies;

    @Override
    public void apply(Project project) {
        boolean isRootProject = project == project.getRootProject();
        if (!isRootProject && project.getRootProject().getPluginManager().hasPlugin("org.openrewrite.rewrite")) {
            return;
        }
        if (project.getRootProject().getPluginManager().hasPlugin("io.moderne.rewrite")) {
            // Moderne plugin provides superset of rewrite plugin functionality, no need to apply both
            return;
        }
        RewriteExtension extension = project.getExtensions().create("rewrite", RewriteExtension.class, project);

        // Rewrite module dependencies put here will be available to all rewrite tasks
        Configuration rewriteConf = project.getConfigurations().maybeCreate("rewrite");
        rewriteConf.setCanBeConsumed(false);

        Provider<ResolvedDependencies> resolvedDependenciesProvider = project.provider(() -> getResolvedDependencies(project, extension, rewriteConf));

        TaskProvider<RewriteRunTask> rewriteRun = project.getTasks().register("rewriteRun", RewriteRunTask.class, task -> {
            task.setExtension(extension);
            task.setResolvedDependencies(resolvedDependenciesProvider);
            task.dependsOn(rewriteConf);
        });

        TaskProvider<RewriteDryRunTask> rewriteDryRun = project.getTasks().register("rewriteDryRun", RewriteDryRunTask.class, task -> {
            task.setExtension(extension);
            task.setResolvedDependencies(resolvedDependenciesProvider);
            task.dependsOn(rewriteConf);
        });

        TaskProvider<RewriteDiscoverTask> rewriteDiscover = project.getTasks().register("rewriteDiscover", RewriteDiscoverTask.class, task -> {
            task.setExtension(extension);
            task.setResolvedDependencies(resolvedDependenciesProvider);
            task.dependsOn(rewriteConf);
        });

        if (isRootProject) {
            project.allprojects(subproject -> configureProject(subproject, extension, rewriteDryRun, rewriteRun));
        } else {
            configureProject(project, extension, rewriteDryRun, rewriteRun);
        }
    }

    private static void configureProject(Project project, RewriteExtension extension, TaskProvider<RewriteDryRunTask> rewriteDryRun, TaskProvider<RewriteRunTask> rewriteRun) {
        // DomainObjectCollection.all() accepts a function to be applied to both existing and subsequently added members of the collection
        // Do not replace all() with any form of collection iteration which does not share this important property
        try {
            project.getPluginManager().apply(JvmEcosystemPlugin.class);
        } catch (NoClassDefFoundError e) {
            // Gradle version is too old to have the JvmEcosystemPlugin, that's OK
        }
        project.getPlugins().all(plugin -> {
            if (plugin instanceof CheckstylePlugin) {
                // A multi-project build could hypothetically have different checkstyle configuration per-project
                // In practice all projects tend to have the same configuration
                CheckstyleExtension checkstyleExtension = project.getExtensions().getByType(CheckstyleExtension.class);
                extension.setCheckstyleConfigProvider(checkstyleExtension::getConfigFile);
                extension.setCheckstylePropertiesProvider(checkstyleExtension::getConfigProperties);
            }
            if (!(plugin instanceof JavaBasePlugin)) {
                return;
            }

            //Collect Java metadata for each project (used for Java Provenance)
            SourceSetContainer sourceSets;
            if (project.getGradle().getGradleVersion().compareTo("7.1") >= 0) {
                sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            } else {
                //Using the older javaConvention because we need to support older versions of gradle.
                try {
                    Method getConventionMethod = Project.class.getDeclaredMethod("getConvention");
                    Class<?> conventionClass = Class.forName("org.gradle.api.plugins.Convention");
                    Method getPluginMethod = conventionClass.getDeclaredMethod("findPlugin", Class.class);
                    Class<?> javaPluginConventionClass = Class.forName("org.gradle.api.plugins.JavaPluginConvention");
                    Method getSourceSetsMethod = javaPluginConventionClass.getDeclaredMethod("getSourceSets");
                    sourceSets = (SourceSetContainer) getSourceSetsMethod.invoke(getPluginMethod.invoke(getConventionMethod.invoke(project), javaPluginConventionClass));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            sourceSets.all(sourceSet -> {
                // This is intended to ensure that any Groovy/Kotlin/etc. and dependent project sources are available
                TaskProvider<Task> compileTask = project.getTasks().named(sourceSet.getCompileJavaTaskName());
                rewriteRun.configure(task -> task.dependsOn(compileTask));
                rewriteDryRun.configure(task -> task.dependsOn(compileTask));
            });

            // Detect SourceSets which overlap other sourceSets and disable the compilation task of the overlapping
            // source set. Some plugins will create source sets not intended to be compiled for their own purposes.
            Set<String> sourceDirs = new HashSet<>();
            project.afterEvaluate(unused -> sourceSets.stream()
                    .sorted(Comparator.comparingInt(sourceSet -> {
                        if ("main".equals(sourceSet.getName())) {
                            return 0;
                        }
                        if ("test".equals(sourceSet.getName())) {
                            return 1;
                        }
                        return 2;
                    })).forEach(sourceSet -> {
                        for (File file : sourceSet.getAllJava().getSourceDirectories().getFiles()) {
                            if (!sourceDirs.add(file.getAbsolutePath())) {
                                TaskProvider<Task> compileTask = project.getTasks().named(sourceSet.getCompileJavaTaskName());
                                compileTask.configure(task -> task.setEnabled(false));
                            }
                        }
                    }));
        });
    }

    public ResolvedDependencies getResolvedDependencies(Project project, RewriteExtension extension, Configuration rewriteConf) {
        if (resolvedDependencies == null) {
            resolvedDependencies = ResolvedDependenciesProvider.get(project, extension, rewriteConf);
        }
        return resolvedDependencies;
    }
}
