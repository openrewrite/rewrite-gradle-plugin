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

import org.gradle.api.Project;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.resources.TextResource;

import java.util.HashSet;
import java.util.Set;

public class RewriteExtension extends CodeQualityExtension {
    private RewriteAction action = RewriteAction.FIX;
    private Set<String> excludeChecks = new HashSet<>();
    private Checkstyle checkstyle = new Checkstyle();
    private boolean showViolations = true;

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
