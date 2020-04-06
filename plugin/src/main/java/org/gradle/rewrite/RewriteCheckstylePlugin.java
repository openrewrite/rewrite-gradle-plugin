package org.gradle.rewrite;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.plugins.quality.internal.AbstractCodeQualityPlugin;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;

public class RewriteCheckstylePlugin extends AbstractCodeQualityPlugin<RewriteCheckstyleTask> {
    private PrometheusMeterRegistry meterRegistry;
    private RewriteExtension extension;

    @Override
    protected String getToolName() {
        return "RewriteCheckstyle";
    }

    @Override
    protected Class<RewriteCheckstyleTask> getTaskType() {
        return RewriteCheckstyleTask.class;
    }

    @Override
    protected CodeQualityExtension createExtension() {
        extension = project.getExtensions().findByType(RewriteExtension.class);
        if (extension == null) {
            extension = project.getExtensions().create("rewrite", RewriteExtension.class, project);
        }
        extension.setToolVersion("2.0");

        return extension;
    }

    @Override
    protected void configureConfiguration(Configuration configuration) {
    }

    @Override
    protected void createConfigurations() {
        // don't need any configuration customization
    }

    @Override
    protected void configureTaskDefaults(RewriteCheckstyleTask task, String baseName) {
        configureMetrics(task);
        configureTaskConventionMapping(task);
        configureReportsConventionMapping(task, baseName);
        runCheckstyleAfterRewriting();
    }

    private void configureMetrics(RewriteCheckstyleTask task) {
        synchronized (Metrics.globalRegistry) {
            if (extension.getMetricsUri() != null && meterRegistry == null) {
                URI uri = URI.create(extension.getMetricsUri());

                ClientTransport clientTransport;
                switch (uri.getScheme()) {
                    case "websocket":
                        clientTransport = WebsocketClientTransport.create(uri);
                        break;
                    case "tcp":
                        clientTransport = TcpClientTransport.create(uri.getHost(), uri.getPort());
                        break;
                    default:
                        project.getLogger().warn("Unable to publish metrics. Unrecognized scheme {}", uri.getScheme());
                        return;
                }

                // one per project, because they will have different tags
                meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                meterRegistry.config()
                        .commonTags(
                                "project.name", project.getName(),
                                "project.display.name", project.getDisplayName(),
                                "project.path", project.getPath(),
                                "project.root.project.name", project.getRootProject().getName(),
                                "gradle.version", project.getGradle().getGradleVersion())
                        .commonTags(extension.getExtraMetricsTags())
                        .meterFilter(new MeterFilter() {
                            @Override
                            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                                if (id.getName().startsWith("rewrite")) {
                                    return DistributionStatisticConfig.builder()
                                            .percentilesHistogram(true)
                                            .maximumExpectedValue(Duration.ofMillis(100).toNanos())
                                            .build()
                                            .merge(config);
                                }
                                return config;
                            }
                        });

                final PrometheusRSocketClient metricsClient = new PrometheusRSocketClient(meterRegistry, clientTransport,
                        c -> c.retryBackoff(Long.MAX_VALUE, Duration.ofSeconds(10), Duration.ofMinutes(10)));

                new JvmMemoryMetrics().bindTo(Metrics.globalRegistry);
                new JvmGcMetrics().bindTo(Metrics.globalRegistry);
                new ProcessorMetrics().bindTo(Metrics.globalRegistry);

                project.getGradle().addBuildListener(new BuildAdapter() {
                    @Override
                    public void buildFinished(BuildResult result) {
                        metricsClient.pushAndClose();
                    }
                });
            }
        }

        if (meterRegistry != null) {
            task.setMeterRegistry(meterRegistry);
        }
    }

    protected void runCheckstyleAfterRewriting() {
        SourceSetContainer sourceSets = getJavaPluginConvention().getSourceSets();
        CheckstylePlugin checkstylePlugin = project.getPlugins().findPlugin(CheckstylePlugin.class);
        if (checkstylePlugin != null) {
            sourceSets.all(sourceSet -> {
                Task rewriteTask = project
                        .getTasksByName(sourceSet.getTaskName(getTaskBaseName(), null), false)
                        .iterator().next();

                Set<Task> checkstyleTasks = project.getTasksByName(sourceSet.getTaskName("checkstyle", null), false);
                if (!checkstyleTasks.isEmpty()) {
                    checkstyleTasks.iterator().next().shouldRunAfter(rewriteTask);
                }
            });
        }
    }

    @Override
    protected void configureForSourceSet(SourceSet sourceSet, RewriteCheckstyleTask task) {
        task.setDescription("Automatically fix Checkstyle issues for " + sourceSet.getName() + " sources");
        task.setSource(sourceSet.getAllJava());
    }

    private void configureTaskConventionMapping(RewriteCheckstyleTask task) {
        ConventionMapping taskMapping = task.getConventionMapping();
        taskMapping.map("config", (Callable<TextResource>) () -> extension.getCheckstyle().getConfig());
        taskMapping.map("ignoreFailures", (Callable<Boolean>) () -> extension.isIgnoreFailures());
        taskMapping.map("showViolations", (Callable<Boolean>) () -> extension.isShowViolations());
        taskMapping.map("action", (Callable<RewriteAction>) () -> extension.getAction());
        taskMapping.map("excludeChecks", (Callable<Set<String>>) () -> extension.getExcludeChecks());
    }

    private void configureReportsConventionMapping(RewriteCheckstyleTask task, String baseName) {
        task.getReports().all(report -> {
            //noinspection UnstableApiUsage
            report.getRequired().convention(true);
            report.getOutputLocation().convention(project.getLayout().getProjectDirectory().file(project.provider(() ->
                    new File(extension.getReportsDir(), baseName + "." + report.getName()).getAbsolutePath())));
        });
    }
}
