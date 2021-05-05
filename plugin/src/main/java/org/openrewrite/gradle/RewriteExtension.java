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

import static java.util.Arrays.asList;

public class RewriteExtension extends CodeQualityExtension {
    private static final String magicalMetricsLogString = "LOG";

    private final List<String> activeRecipes = new ArrayList<>();
    private final List<String> activeStyles = new ArrayList<>();
    private boolean configFileSetDeliberately = false;
    private final Project project;
    private File configFile;
    private String metricsUri = magicalMetricsLogString;
    private String rewriteVersion = "7.3.0";

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    private boolean failOnInvalidActiveRecipes = false;

    @SuppressWarnings("unused")
    public RewriteExtension(Project project) {
        this.project = project;
        configFile = project.getRootProject().file("rewrite.yml");
    }

    public void setConfigFile(File configFile) {
        configFileSetDeliberately = true;
        this.configFile = configFile;
    }

    public void setConfigFile(String configFilePath) {
        configFileSetDeliberately = true;
        configFile = project.file(configFilePath);
    }

    /**
     * Supplying a rewrite configuration file is optional, so if it doesn't exist it isn't an error or a warning.
     * But if the user has deliberately specified a different location from the default, that seems like a reasonable
     * signal that the file should be expected to exist. So this signal can be used to decide if a warning should be
     * displayed if the specified file cannot be found.
     */
    boolean getConfigFileSetDeliberately() {
        return configFileSetDeliberately;
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

    public void activeRecipe(String... recipes) {
        activeRecipes.addAll(asList(recipes));
    }

    public void clearActiveRecipes() {
        activeRecipes.clear();
    }

    public void setActiveRecipes(List<String> activeRecipes) {
        this.activeRecipes.clear();
        this.activeRecipes.addAll(activeRecipes);
    }

    public void activeStyle(String... styles) {
        activeStyles.addAll(asList(styles));
    }

    public void clearActiveStyles() {
        activeStyles.clear();
    }

    public void setActiveStyles(List<String> activeStyles) {
        this.activeRecipes.clear();
        this.activeRecipes.addAll(activeStyles);
    }

    public List<String> getActiveStyles() {
        return activeStyles;
    }

    public List<String> getActiveRecipes() {
        return activeRecipes;
    }

    public String getRewriteVersion() {
        return rewriteVersion;
    }

    public void setRewriteVersion(String value) {
        rewriteVersion = value;
    }

    public boolean getFailOnInvalidActiveRecipes() {
        return failOnInvalidActiveRecipes;
    }

    public void setFailOnInvalidActiveRecipes(boolean failOnInvalidActiveRecipes) {
        this.failOnInvalidActiveRecipes = failOnInvalidActiveRecipes;
    }
}
