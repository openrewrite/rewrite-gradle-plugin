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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;

import java.time.Duration;

public interface RewritePlugin extends Plugin<ProjectInternal> {
    default void configureMetrics(RewriteTask task) {
        Project project = task.getProject();

        RewriteMetricsPlugin metricsPlugin = project.getRootProject().getPlugins().findPlugin(RewriteMetricsPlugin.class);

        getMeterRegistry().config()
                .commonTags(
                        "project.name", project.getName(),
                        "project.display.name", project.getDisplayName(),
                        "project.path", project.getPath(),
                        "project.root.project.name", project.getRootProject().getName(),
                        "gradle.version", project.getGradle().getGradleVersion(),
                        "rewrite.plan.name", getRewritePlanName()
                )
                .commonTags(metricsPlugin != null ? metricsPlugin.getExtraTags() : Tags.empty())
                .meterFilter(new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                        if (id.getName().equals("rewrite.parse")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .maximumExpectedValue((double) Duration.ofMillis(250).toNanos())
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });

        task.setMeterRegistry(getMeterRegistry());
    }

    String getRewritePlanName();

    PrometheusMeterRegistry getMeterRegistry();
}
