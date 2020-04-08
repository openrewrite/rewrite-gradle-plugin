package org.gradle.rewrite;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Collectors;

import static io.rsocket.transport.netty.UriUtils.getPort;

/**
 * Coordinates the publication of metrics for each rewrite plugin and each subproject along with general purpose metrics
 * related to JVM health to a Prometheus proxy.
 */
public class RewriteMetricsPlugin implements Plugin<Project> {
    private RewriteMetricsExtension extension;
    private final PrometheusMeterRegistry rootProjectMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Override
    public void apply(Project project) {
        if (project.getRootProject() == project) {
            this.extension = project.getExtensions().create("rewriteMetrics", RewriteMetricsExtension.class);

            project.afterEvaluate(p -> {
                rootProjectMeterRegistry.config()
                        .commonTags(
                                "project.root.project.name", project.getName(),
                                "gradle.version", project.getGradle().getGradleVersion()
                        )
                        .commonTags(extension.getExtraMetricsTags());

                new JvmGcMetrics().bindTo(rootProjectMeterRegistry);
                new JvmMemoryMetrics().bindTo(rootProjectMeterRegistry);
                new JvmHeapPressureMetrics().bindTo(rootProjectMeterRegistry);
                new ProcessorMetrics().bindTo(rootProjectMeterRegistry);

                connectToPrometheusProxy(project);
            });
        } else {
            project.getLogger().warn("org.gradle.rewrite-metrics should only be applied to the root project");
        }
    }

    /**
     * Connects "after-evaluate" and disconnects on build finish so that the meter registries themselves can remain in
     * the daemon between runs, but metrics are only published when the build is running (plus a fire-and-forget push
     * when the build finishes).
     *
     * @param project The root project.
     */
    private void connectToPrometheusProxy(Project project) {
        URI uri = extension.getMetricsUri();
        if (uri != null) {
            ClientTransport clientTransport;
            switch (uri.getScheme()) {
                case "ephemeral":
                case "https":
                case "wss": {
                    TcpClient tcpClient = TcpClient.create().secure().host(uri.getHost()).port(getPort(uri, 443));
                    clientTransport = websocketClientTransport(tcpClient);
                    break;
                }
                case "http":
                case "ws": {
                    TcpClient tcpClient = TcpClient.create().host(uri.getHost()).port(getPort(uri, 80));
                    clientTransport = websocketClientTransport(tcpClient);
                    break;
                }
                case "tcp":
                    clientTransport = TcpClientTransport.create(uri.getHost(), uri.getPort());
                    break;
                default:
                    project.getLogger().warn("Unable to publish metrics. Unrecognized scheme {}", uri.getScheme());
                    return;
            }

            PrometheusRSocketClient metricsClient = PrometheusRSocketClient
                    .build(
                            rootProjectMeterRegistry,
                            () -> rootProjectMeterRegistry.scrape() + scrapeFromEachProject(project),
                            clientTransport
                    )
                    .customizeAndRetry(r -> r.retryBackoff(Long.MAX_VALUE, Duration.ofSeconds(1), Duration.ofSeconds(3)))
                    .connect();

            project.getGradle().addBuildListener(new BuildAdapter() {
                @Override
                public void buildFinished(BuildResult result) {
                    try {
                        metricsClient.pushAndClose();
                    } catch (Throwable ignore) {
                        // sometimes fails when connection already closed, e.g. due to flaky internet connection
                    }
                }
            });
        }
    }

    private String scrapeFromEachProject(Project rootProject) {
        return rootProject.getAllprojects().stream()
                .flatMap(p -> p.getPlugins().withType(RewritePlugin.class).stream()
                        .map(plugin -> plugin.getMeterRegistry().scrape()))
                .collect(Collectors.joining(""));
    }

    @NotNull
    private ClientTransport websocketClientTransport(TcpClient tcpClient) {
        HttpClient httpClient = HttpClient.from(tcpClient).wiretap(true);
        if (extension.getMetricsUsername() != null && extension.getMetricsPassword() != null) {
            httpClient = httpClient.headers(h -> h.add("Authorization", "Basic: " + Base64.getUrlEncoder()
                    .encodeToString((extension.getMetricsUsername() + ":" + extension.getMetricsPassword()).getBytes())));
        }
        return WebsocketClientTransport.create(httpClient, "/");
    }

    Iterable<Tag> getExtraTags() {
        return extension.getExtraMetricsTags();
    }
}
