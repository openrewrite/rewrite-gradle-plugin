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

import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;

public class RewriteExtension extends CodeQualityExtension {
    private static final String magicalMetricsLogString = "LOG";

    private final List<String> activeRecipes = new ArrayList<>();
    private final List<String> activeStyles = new ArrayList<>();
    private boolean configFileSetDeliberately = false;
    private final Project project;
    private File configFile;
    Provider<File> checkstyleConfigProvider;
    Provider<Map<String,Object>> checkstylePropertiesProvider;
    private File checkstyleConfigFile;
    private String metricsUri = magicalMetricsLogString;

    @Nullable
    private String rewriteVersion;

    @Nullable
    private Properties versionProps;

    private boolean logCompilationWarningsAndErrors;

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    private boolean failOnInvalidActiveRecipes = false;

    private boolean failOnDryRunResults = false;

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

    public void setCheckstyleConfigFile(File configFile) {
        this.checkstyleConfigFile = configFile;
    }

    /**
     * Will prefer to return an explicitly configured checkstyle configuration file location.
     * If none has been specified, will attempt to auto-detect an appropriate file.
     */
    @Nullable
    public File getCheckstyleConfigFile() {
        if(checkstyleConfigFile == null && checkstyleConfigProvider != null) {
            return checkstyleConfigProvider.get();
        }
        return checkstyleConfigFile;
    }

    public Map<String, Object> getCheckstyleProperties() {
        if(checkstyleConfigProvider == null) {
            return emptyMap();
        }
        return checkstylePropertiesProvider.get();
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

    private Properties getVersionProps() {
        if(versionProps == null) {
            if(rewriteVersion == null) {
                try(InputStream is = RewriteExtension.class.getResourceAsStream("/versions.properties")) {
                    versionProps = new Properties();
                    versionProps.load(is);
                } catch(IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return versionProps;
    }

    /**
     * Returns the version of rewrite core libraries to be used.
     */
    public String getRewriteVersion() {
        if(rewriteVersion == null) {
            return getVersionProps().getProperty("org.openrewrite:rewrite-core");
        }
        return rewriteVersion;
    }

    public String getCheckstyleToolsVersion() {
        return getVersionProps().getProperty("com.puppycrawl.tools:checkstyle");
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

    public boolean getFailOnDryRunResults() {
        return this.failOnDryRunResults;
    }

    public void setFailOnDryRunResults(boolean failOnDryRunResults) {
        this.failOnDryRunResults = failOnDryRunResults;
    }

    public boolean getLogCompilationWarningsAndErrors() {
        return logCompilationWarningsAndErrors;
    }

    public void setLogCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
        this.logCompilationWarningsAndErrors = logCompilationWarningsAndErrors;
    }
}
