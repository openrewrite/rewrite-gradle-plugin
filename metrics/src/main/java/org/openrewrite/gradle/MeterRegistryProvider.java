/*
 * Copyright 2021 the original author or authors.
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

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.prometheus.client.CollectorRegistry;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.gradle.api.logging.Logger;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

import static org.openrewrite.gradle.RewriteMetricsPlugin.getPort;

public class MeterRegistryProvider implements AutoCloseable {
    private final Logger log;
    private final String uriString;
    private final String username;
    private final String password;

    private PrometheusRSocketClient metricsClient;
    private MeterRegistry registry;

    public MeterRegistryProvider(Logger log, String uriString, String username, String password) {
        this.log = log;
        this.uriString = uriString;
        this.username = username;
        this.password = password;
    }

    public MeterRegistry registry() {
        this.registry = buildRegistry();
        return this.registry;
    }

    private MeterRegistry buildRegistry() {
        if (uriString == null) {
            return new CompositeMeterRegistry();
        } else if ("LOG".equals(uriString)) {
            return new GradleLoggingMeterRegistry(log);
        } else {
            try {
                URI uri = URI.create(uriString);
                PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new CollectorRegistry(), Clock.SYSTEM);

                ClientTransport clientTransport;
                switch (uri.getScheme()) {
                    case "ephemeral":
                    case "https":
                    case "wss": {
                        TcpClient tcpClient = TcpClient.create().secure().host(uri.getHost()).port(getPort(uri, 443));
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "http":
                    case "ws": {
                        TcpClient tcpClient = TcpClient.create().host(uri.getHost()).port(getPort(uri, 80));
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "tcp":
                        clientTransport = TcpClientTransport.create(uri.getHost(), uri.getPort());
                        break;
                    default:
                        log.warn("Unable to publish metrics. Unrecognized scheme {}", uri.getScheme());
                        return new CompositeMeterRegistry();
                }

                metricsClient = PrometheusRSocketClient
                        .build(registry, registry::scrape, clientTransport)
                        .retry(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(3)))
                        .connect();

                return registry;
            } catch (Throwable t) {
                log.warn("Unable to publish metrics", t);
            }
        }

        return new CompositeMeterRegistry();
    }

    private ClientTransport getWebsocketClientTransport(TcpClient tcpClient) {
        HttpClient httpClient = HttpClient.from(tcpClient).wiretap(true);
        if (username != null && password != null) {
            httpClient = httpClient.headers(h -> h.add("Authorization", "Basic: " + Base64.getUrlEncoder()
                    .encodeToString((username + ":" + password).getBytes())));
        }
        return WebsocketClientTransport.create(httpClient, "/");
    }

    @Override
    public void close() throws Exception {
        if (metricsClient != null) {
            try {
                // The push and close will block for one second. If the build ends before the dying push can happen, so be it.
                metricsClient.pushAndClose();
            } catch (Throwable ignore) {
                // sometimes fails when connection already closed, e.g. due to flaky internet connection
            }
        }

        if (registry != null) {
            registry.close();
        }
    }
}
