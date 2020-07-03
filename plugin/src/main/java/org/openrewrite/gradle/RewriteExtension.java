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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RewriteExtension extends CodeQualityExtension {
    private static final String magicalMetricsLogString = "LOG";

    private boolean showViolations = true;
    private final List<String> activeProfiles = new ArrayList<>();
    private Project project;
    private File configFile;
    private final List<GradleProfileConfiguration> profiles = new ArrayList<>();
    private String metricsUri = magicalMetricsLogString;

    @SuppressWarnings("unused")
    public RewriteExtension(Project project) {
        this.project = project;
        configFile = project.file("rewrite.yml");
    }

    public boolean getShowViolations() {
        return showViolations;
    }

    public void setShowViolations(boolean showViolations) {
        this.showViolations = showViolations;
    }

    public void setConfigFile(File configFile) {
        this.configFile = configFile;
    }

    public void setConfigFile(String configFilePath) {
        configFile = project.file(configFilePath);
    }

    public File getConfigFile() {
        return configFile;
    }

    public void enableRouteMetricsToLog() {
        metricsUri = magicalMetricsLogString;
    }
    public boolean isRouteMetricsToLog() {
        return metricsUri.equals(magicalMetricsLogString);
    }
    public String getMetricsUri() {
        return metricsUri;
    }
    public void setMetricsUri(String value) {
        metricsUri = value;
    }


    public void activeProfile(String... profiles) {
        activeProfiles.addAll(List.of(profiles));
    }

    public void clearActiveProfiles() {
        activeProfiles.clear();
    }
    public void setActiveProfile(List<String> activeProfiles) {
        this.activeProfiles.clear();
        this.activeProfiles.addAll(activeProfiles);
    }
    public List<String> getActiveProfiles() {
        return List.copyOf(activeProfiles);
    }

    public void profile(GradleProfileConfiguration ... profiles) {
        this.profiles.addAll(List.of(profiles));
    }

    public List<GradleProfileConfiguration> getProfiles() {
        return List.copyOf(profiles);
    }

}
