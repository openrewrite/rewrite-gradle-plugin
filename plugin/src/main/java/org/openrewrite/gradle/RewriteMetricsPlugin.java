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
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

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
                    .retry(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(3)))
                    .connect();

            project.getGradle().addBuildListener(new BuildAdapter() {
                @Override
                public void buildFinished(BuildResult result) {
                    try {
                        // Don't bother blocking long here. If the daemon dies before the dying push can happen, so be it.
                        metricsClient.pushAndClose().block(Duration.ofSeconds(3));
                    } catch (Throwable ignore) {
                        // sometimes fails when connection already closed, e.g. due to flaky internet connection
                        ignore.printStackTrace();
                    }
                }
            });
        }
    }

    private String scrapeFromEachProject(Project rootProject) {
        throw new RuntimeException("Not implemented. Put this back in once metrics have been wired back in to RewritePlugin");
//        return rootProject.getAllprojects().stream()
//                .flatMap(p -> p.getPlugins().withType(RewritePlugin.class).stream()
//                        .map(plugin -> plugin.getMeterRegistry().scrape()))
//                .collect(Collectors.joining(""));
    }

    private ClientTransport websocketClientTransport(TcpClient tcpClient) {
        HttpClient httpClient = HttpClient.from(tcpClient).wiretap(true);
        if (extension.getMetricsUsername() != null && extension.getMetricsPassword() != null) {
            httpClient = httpClient.headers(h -> h.add("Authorization", "Basic: " + Base64.getUrlEncoder()
                    .encodeToString((extension.getMetricsUsername() + ":" + extension.getMetricsPassword()).getBytes())));
        }
        return WebsocketClientTransport.create(httpClient, "/");
    }

    /**
     * Returns the port of a URI. If the port is unset (i.e. {@code -1}) then returns the {@code
     * defaultPort}.
     *
     * @param uri         the URI to extract the port from
     * @param defaultPort the default to use if the port is unset
     * @return the port of a URI or {@code defaultPort} if unset
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    static int getPort(URI uri, int defaultPort) {
        Objects.requireNonNull(uri, "uri must not be null");
        return uri.getPort() == -1 ? defaultPort : uri.getPort();
    }

    Iterable<Tag> getExtraTags() {
        return extension.getExtraMetricsTags();
    }
}
