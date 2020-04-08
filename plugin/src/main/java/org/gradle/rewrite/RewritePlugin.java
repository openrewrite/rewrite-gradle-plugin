package org.gradle.rewrite;

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
