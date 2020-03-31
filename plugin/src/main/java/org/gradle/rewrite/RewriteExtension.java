package org.gradle.rewrite;

import org.gradle.api.Project;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.resources.TextResource;

public class RewriteExtension extends CodeQualityExtension {
    private boolean fixInPlace = true;
    private boolean autoCommit = false;
    private Checkstyle checkstyle = new Checkstyle();

    @SuppressWarnings("unused")
    public RewriteExtension(Project project) {
    }

    public boolean isFixInPlace() {
        return fixInPlace;
    }

    public void setFixInPlace(boolean fixInPlace) {
        this.fixInPlace = fixInPlace;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
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
