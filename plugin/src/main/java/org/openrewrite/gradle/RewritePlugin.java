/*
 * Copyright 2020 the original author or authors.
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

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.tasks.SourceSet;

import java.util.HashSet;
import java.util.Set;

/**
 * Adds the RewriteExtension to the current project and registers tasks per-sourceSet.
 * Only needs to be applied to projects with java sources. No point in applying this to any project that does
 * not have java sources of its own, such as the root project in a multi-project builds.
 */
public class RewritePlugin implements Plugin<Project> {
    /*
     Note on compatibility:
     Since we're in the software modernization and improvement business we want to support old versions of Gradle.
     As written this project doesn't use any APIs not present as of Gradle 4.7.
     That predates Gradle supporting Java 11, which came in Gradle 5.0.
     Our automated tests wont currently _enforce_ this compatibility guarantee.
     Until that changes, tread carefully and test manually if you introduce any new usage of any Gradle API.
     */

    @Override
    public void apply(Project rootProject) {
        // Only apply to the root project
        if(!rootProject.getPath().equals(":")) {
            return;
        }
        RewriteExtension maybeExtension = rootProject.getExtensions().findByType(RewriteExtension.class);
        if (maybeExtension == null) {
            maybeExtension = rootProject.getExtensions().create("rewrite", RewriteExtension.class, rootProject);
            maybeExtension.setToolVersion("2.x");
        }
        final RewriteExtension extension = maybeExtension;

        // Rewrite module dependencies put here will be available to all rewrite tasks
        Configuration rewriteConf = rootProject.getConfigurations().maybeCreate("rewrite");

        Set<SourceSet> sourceSets = new HashSet<>();
        Task rewriteRun = rootProject.getTasks().create("rewriteRun", RewriteRunTask.class, rewriteConf, sourceSets, extension);
        Task rewriteDryRun = rootProject.getTasks().create("rewriteDryRun", RewriteDryRunTask.class, rewriteConf, sourceSets, extension);
        Task rewriteDiscover = rootProject.getTasks().create("rewriteDiscover", RewriteDiscoverTask.class, rewriteConf, sourceSets, extension);

        rootProject.allprojects(project -> {
            // DomainObjectCollection.all() accepts a function to be applied to both existing and subsequently added members of the collection
            // Do not replace all() with any form of collection iteration which does not share this important property
            project.getPlugins().all(plugin -> {
                if(plugin instanceof CheckstylePlugin) {
                    // A multi-project build could hypothetically have different checkstyle configuration per-project
                    // In practice all projects tend to have the same configuration
                    CheckstyleExtension checkstyleExtension = project.getExtensions().getByType(CheckstyleExtension.class);
                    extension.checkstyleConfigProvider = checkstyleExtension::getConfigFile;
                    extension.checkstylePropertiesProvider = checkstyleExtension::getConfigProperties;
                }
                if(!(plugin instanceof JavaBasePlugin)) {
                    return;
                }
                JavaPluginConvention javaConvention = project.getConvention().getPlugin(JavaPluginConvention.class);
                javaConvention.getSourceSets().all(sourceSet -> {
                    sourceSets.add(sourceSet);

                    // This is intended to ensure that any Groovy/Kotlin/etc. sources are available for type attribution during parsing
                    // This may not be necessary if sourceSet.getCompileClasspath() guarantees that such sources will have been compiled
                    Task compileTask = project.getTasks().getByPath(sourceSet.getCompileJavaTaskName());
                    rewriteRun.dependsOn(compileTask);
                    rewriteDryRun.dependsOn(compileTask);
                });
            });
        });
    }

    private Closure<Task> taskClosure(Action<Task> configFun) {
        return new Closure<Task>(this) {
            public void doCall(Task arg) {
                configFun.execute(arg);
            }
        };
    }

//    void configureMetrics(RewriteTask task) {
//        Project project = task.getProject();
//
//        RewriteMetricsPlugin metricsPlugin = project.getRootProject().getPlugins().findPlugin(RewriteMetricsPlugin.class);
//
//        getMeterRegistry().config()
//                .commonTags(
//                        "project.name", project.getName(),
//                        "project.display.name", project.getDisplayName(),
//                        "project.path", project.getPath(),
//                        "project.root.project.name", project.getRootProject().getName(),
//                        "gradle.version", project.getGradle().getGradleVersion(),
//                        "rewrite.plan.name", getRewritePlanName()
//                )
//                .commonTags(metricsPlugin != null ? metricsPlugin.getExtraTags() : Tags.empty())
//                .meterFilter(new MeterFilter() {
//                    @Override
//                    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
//                        if (id.getName().equals("rewrite.parse")) {
//                            return DistributionStatisticConfig.builder()
//                                    .percentilesHistogram(true)
//                                    .maximumExpectedValue((double) Duration.ofMillis(250).toNanos())
//                                    .build()
//                                    .merge(config);
//                        }
//                        return config;
//                    }
//                });
//
//        task.setMeterRegistry(getMeterRegistry());
//    }
//
//    String getRewritePlanName();
//
//    PrometheusMeterRegistry getMeterRegistry();
}
