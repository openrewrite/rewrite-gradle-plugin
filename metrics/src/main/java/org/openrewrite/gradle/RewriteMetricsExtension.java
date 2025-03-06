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

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class RewriteMetricsExtension {
    /**
     * Metrics destination such as "LOG".
     */
    private String metricsDestination;
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

    @Nullable
    public String getMetricsDestination() {
        return metricsDestination;
    }

    public void setMetricsDestination(String metricsDestination) {
        this.metricsDestination = metricsDestination;
    }

    public Iterable<Tag> getExtraMetricsTags() {
        return extraMetricsTags;
    }

    public void setExtraMetricsTags(Iterable<Tag> extraMetricsTags) {
        this.extraMetricsTags = extraMetricsTags;
    }
}
