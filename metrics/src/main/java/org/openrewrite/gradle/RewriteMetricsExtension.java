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
