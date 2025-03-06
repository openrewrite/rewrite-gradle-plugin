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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Coordinates the publication of metrics for each rewrite plugin and each subproject along with general purpose metrics
 * related to JVM health to a Prometheus proxy.
 */
public class RewriteMetricsPlugin implements Plugin<Project> {
    private RewriteMetricsExtension extension;

    @Override
    public void apply(Project project) {
        if (project.getRootProject() == project) {
            MeterRegistry meterRegistry =
                    new MeterRegistryProvider(project.getLogger(), extension.getMetricsDestination()).registry();
            this.extension = project.getExtensions().create("rewriteMetrics", RewriteMetricsExtension.class);

            project.afterEvaluate(p -> {
                meterRegistry.config()
                        .commonTags(
                                "project.root.project.name", project.getName(),
                                "gradle.version", project.getGradle().getGradleVersion()
                        )
                        .commonTags(extension.getExtraMetricsTags());

                new JvmGcMetrics().bindTo(meterRegistry);
                new JvmMemoryMetrics().bindTo(meterRegistry);
                new JvmHeapPressureMetrics().bindTo(meterRegistry);
                new ProcessorMetrics().bindTo(meterRegistry);
            });
        } else {
            project.getLogger().warn("org.gradle.rewrite-metrics should only be applied to the root project");
        }
    }

    private String scrapeFromEachProject(Project rootProject) {
        throw new RuntimeException("Not implemented. Put this back in once metrics have been wired back into RewritePlugin");
//        return rootProject.getAllprojects().stream()
//                .flatMap(p -> p.getPlugins().withType(RewritePlugin.class).stream()
//                        .map(plugin -> plugin.getMeterRegistry().scrape()))
//                .collect(Collectors.joining(""));
    }

    Iterable<Tag> getExtraTags() {
        return extension.getExtraMetricsTags();
    }
}
