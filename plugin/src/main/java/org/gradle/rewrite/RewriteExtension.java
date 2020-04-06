package org.gradle.rewrite;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.gradle.api.Project;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.resources.TextResource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

public class RewriteExtension extends CodeQualityExtension {
    private RewriteAction action = RewriteAction.FIX;
    private Set<String> excludeChecks = new HashSet<>();
    private Checkstyle checkstyle = new Checkstyle();
    private boolean showViolations = true;

    /**
     * In the form tcp://host:port, http://host:port, https://host:port, ws://host:port, wss://host:port. Port is optional.
     */
    private String metricsUri;
    private String metricsUsername;
    private String metricsPassword;

    /**
     * In addition to a set of tags like Gradle project name, root project name, group, etc. that are
     * preconfigured.
     */
    private Iterable<Tag> extraMetricsTags = Tags.empty();

    private RewriteExtension() {
        try {
            extraMetricsTags = Tags.concat(extraMetricsTags, "host", InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException ignored) {
        }
    }

    @SuppressWarnings("unused")
    public RewriteExtension(Project project) {
    }

    public RewriteAction getAction() {
        return action;
    }

    public void setAction(RewriteAction action) {
        this.action = action;
    }

    public Set<String> getExcludeChecks() {
        return excludeChecks;
    }

    public void setExcludeChecks(Set<String> excludeChecks) {
        this.excludeChecks = excludeChecks;
    }

    public boolean isShowViolations() {
        return showViolations;
    }

    public void setShowViolations(boolean showViolations) {
        this.showViolations = showViolations;
    }

    public String getMetricsUri() {
        return metricsUri;
    }

    public void setMetricsUri(String metricsUri) {
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

    public Checkstyle getCheckstyle() {
        return checkstyle;
    }

    public void setCheckstyle(Checkstyle checkstyle) {
        this.checkstyle = checkstyle;
    }

    public static class Checkstyle {
        private TextResource config;

        public TextResource getConfig() {
            return config;
        }

        public void setConfig(TextResource config) {
            this.config = config;
        }
    }
}
