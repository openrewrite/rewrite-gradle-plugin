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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Adds the RewriteExtension to the current project and registers tasks per-sourceSet that implement rewrite fixing and
 * warning. Only needs to be applied to projects with java sources. No point in applying this to any project that does
 * not have java sources of its own, such as the root project in a multi-project builds.
 */
public class RewritePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RewriteExtension maybeExtension = project.getExtensions().findByType(RewriteExtension.class);
        if(maybeExtension == null) {
            maybeExtension = project.getExtensions().create("rewrite", RewriteExtension.class, project);
            maybeExtension.setToolVersion("2.x");
        }
        final RewriteExtension extension = maybeExtension;
        TaskContainer tasks = project.getTasks();

        JavaPluginConvention javaPlugin = project.getConvention().getPlugin(JavaPluginConvention.class);
        SourceSetContainer sourceSets = javaPlugin.getSourceSets();

        // Fix is meant to be invoked manually and so is not made a dependency of any existing task
        TaskProvider<Task> rewriteFixAllProvider = tasks.register("rewriteFix");
        rewriteFixAllProvider.configure(it -> {
            it.setGroup("rewrite");
            it.setDescription("Apply the active refactoring recipes to all sources");
        });
        // Warn hooks into the regular Java build by becoming a dependency of "check", the same way that checkstyle or unit tests do
        TaskProvider<Task> rewriteWarnAllProvider = tasks.register("rewriteWarn");
        rewriteWarnAllProvider.configure(it -> {
            it.setGroup("rewrite");
            it.setDescription("Dry run the active refactoring recipes to all sources. No changes will be made.");
        });
        TaskProvider<?> checkTaskProvider = tasks.named("check");
        checkTaskProvider.configure(checkTask -> checkTask.dependsOn(rewriteWarnAllProvider));

        sourceSets.configureEach(sourceSet -> {

            String rewriteFixTaskName = "rewriteFix" + sourceSet.getName().substring(0, 1).toUpperCase() + sourceSet.getName().substring(1);
            TaskProvider<RewriteFixTask> rewriteFixProvider = tasks.register(rewriteFixTaskName, RewriteFixTask.class, sourceSet, extension);
            rewriteFixAllProvider.configure(it -> it.dependsOn(rewriteFixProvider));

            String rewriteDiscoverTaskName = "rewriteDiscover" + sourceSet.getName().substring(0, 1).toUpperCase() + sourceSet.getName().substring(1);
            tasks.register(rewriteDiscoverTaskName, RewriteDiscoverTask.class, sourceSet, extension);

            String compileTaskName = sourceSet.getCompileTaskName("java");
            TaskProvider<?> compileTaskProvider = tasks.named(compileTaskName);
            compileTaskProvider.configure(compileTask -> compileTask.mustRunAfter(rewriteFixProvider));

            String rewriteWarnTaskName = "rewriteWarn" + sourceSet.getName().substring(0, 1).toUpperCase() + sourceSet.getName().substring(1);
            TaskProvider<RewriteWarnTask> rewriteWarnProvider = tasks.register(rewriteWarnTaskName, RewriteWarnTask.class, sourceSet, extension);
            rewriteWarnAllProvider.configure(it -> it.dependsOn(rewriteWarnProvider));
        });
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
