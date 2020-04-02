package org.gradle.rewrite;

import org.gradle.api.Project;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.resources.TextResource;

import java.util.HashSet;
import java.util.Set;

public class RewriteExtension extends CodeQualityExtension {
    private RewriteAction action = RewriteAction.FIX_SOURCE;
    private Set<String> excludeChecks = new HashSet<>();
    private Checkstyle checkstyle = new Checkstyle();

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
