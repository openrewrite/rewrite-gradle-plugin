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

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public class RewriteMetricsExtension {
    /**
     * In the form tcp://host:port, http://host:port, https://host:port, ws://host:port, wss://host:port. Port is optional.
     */
    private URI metricsUri;

    private String metricsUsername;
    private String metricsPassword;

    /**
     * In addition to a set of tags like Gradle project name, root project name, group, etc. that are
     * preconfigured.
     */
    private Iterable<Tag> extraMetricsTags = Tags.empty();

    public RewriteMetricsExtension() {
        try {
            extraMetricsTags = Tags.concat(extraMetricsTags, "host", InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException ignored) {
        }
    }

    public URI getMetricsUri() {
        return metricsUri;
    }

    public void setMetricsUri(URI metricsUri) {
        this.metricsUri = metricsUri;
    }

    public Iterable<Tag> getExtraMetricsTags() {
        return extraMetricsTags;
    }

    public void setExtraMetricsTags(Iterable<Tag> extraMetricsTags) {
        this.extraMetricsTags = extraMetricsTags;
    }

    public String getMetricsUsername() {
        return metricsUsername;
    }

    public void setMetricsUsername(String metricsUsername) {
        this.metricsUsername = metricsUsername;
    }

    public String getMetricsPassword() {
        return metricsPassword;
    }

    public void setMetricsPassword(String metricsPassword) {
        this.metricsPassword = metricsPassword;
    }
}
