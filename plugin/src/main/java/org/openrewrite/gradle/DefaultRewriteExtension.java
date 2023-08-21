/*
 * Copyright ${year} the original author or authors.
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

import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;

@SuppressWarnings("unused")
public class DefaultRewriteExtension implements RewriteExtension {
    private static final String magicalMetricsLogString = "LOG";

    private final List<String> activeRecipes = new ArrayList<>();
    private final List<String> activeStyles = new ArrayList<>();
    private boolean configFileSetDeliberately;
    protected final Project project;
    private File configFile;

    private Provider<File> checkstyleConfigProvider;
    private Provider<Map<String,Object>> checkstylePropertiesProvider;
    private File checkstyleConfigFile;
    private String metricsUri = magicalMetricsLogString;
    private boolean enableExperimentalGradleBuildScriptParsing = true;
    private final List<String> exclusions = new ArrayList<>();
    private final List<String> plainTextMasks = new ArrayList<>();

    private int sizeThresholdMb = 10;

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
    private boolean failOnInvalidActiveRecipes;

    private boolean failOnDryRunResults;

    private boolean throwOnParseFailures;

    @SuppressWarnings("unused")
    public DefaultRewriteExtension(Project project) {
        this.project = project;
        configFile = project.file("rewrite.yml");
    }

    @Override
    public void setConfigFile(File configFile) {
        configFileSetDeliberately = true;
        this.configFile = configFile;
    }

    @Override
    public void setConfigFile(String configFilePath) {
        configFileSetDeliberately = true;
        configFile = project.file(configFilePath);
    }

    @Override
    public void setCheckstyleConfigFile(File configFile) {
        this.checkstyleConfigFile = configFile;
    }

    /**
     * Will prefer to return an explicitly configured checkstyle configuration file location.
     * If none has been specified, will attempt to auto-detect an appropriate file.
     */
    @Override
    @Nullable
    public File getCheckstyleConfigFile() {
        if(checkstyleConfigFile == null && checkstyleConfigProvider != null) {
            try {
                return checkstyleConfigProvider.get();
            } catch(Exception e) {
                return null;
            }
        }
        return checkstyleConfigFile;
    }

    @Override
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
    @Override
    public boolean getConfigFileSetDeliberately() {
        return configFileSetDeliberately;
    }

    @Override
    public File getConfigFile() {
        return configFile;
    }

    @Override
    public void enableRouteMetricsToLog() {
        metricsUri = magicalMetricsLogString;
    }

    @Override
    public boolean isRouteMetricsToLog() {
        return metricsUri.equals(magicalMetricsLogString);
    }

    @Override
    public String getMetricsUri() {
        return metricsUri;
    }

    @Override
    public void setMetricsUri(String value) {
        metricsUri = value;
    }

    @Override
    public void activeRecipe(String... recipes) {
        activeRecipes.addAll(asList(recipes));
    }

    @Override
    public void clearActiveRecipes() {
        activeRecipes.clear();
    }

    @Override
    public void setActiveRecipes(List<String> activeRecipes) {
        this.activeRecipes.clear();
        this.activeRecipes.addAll(activeRecipes);
    }

    @Override
    public void activeStyle(String... styles) {
        activeStyles.addAll(asList(styles));
    }

    @Override
    public void clearActiveStyles() {
        activeStyles.clear();
    }

    @Override
    public void setActiveStyles(List<String> activeStyles) {
        this.activeRecipes.clear();
        this.activeRecipes.addAll(activeStyles);
    }

    @Override
    public List<String> getActiveStyles() {
        return activeStyles;
    }

    @Override
    public List<String> getActiveRecipes() {
        return activeRecipes;
    }

    private Properties getVersionProps() {
        if(versionProps == null) {
            try(InputStream is = DefaultRewriteExtension.class.getResourceAsStream("/rewrite/versions.properties")) {
                versionProps = new Properties();
                versionProps.load(is);
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
        }
        return versionProps;
    }

    /**
     * Returns the version of rewrite core libraries to be used.
     */
    @Override
    public String getRewriteVersion() {
        if(rewriteVersion == null) {
            return getVersionProps().getProperty("org.openrewrite:rewrite-core");
        }
        return rewriteVersion;
    }

    private String rewriteGradleModelVersion;
    @Override
    public String getRewriteGradleModelVersion() {
        if(rewriteGradleModelVersion == null) {
            rewriteGradleModelVersion = getVersionProps().getProperty("org.openrewrite.gradle.tooling:model");
        }
        return rewriteGradleModelVersion;
    }

    private String rewriteKotlinVersion;
    @Override
    public String getRewriteKotlinVersion() {
        if(rewriteKotlinVersion == null) {
            rewriteKotlinVersion = getVersionProps().getProperty("org.openrewrite:rewrite-kotlin");
        }
        return rewriteKotlinVersion;
    }

    private String rewritePythonVersion;
    @Override
    public String getRewritePythonVersion() {
        if(rewritePythonVersion == null) {
            rewritePythonVersion = getVersionProps().getProperty("org.openrewrite:rewrite-python");
        }
        return rewritePythonVersion;
    }

    @Override
    public String getCheckstyleToolsVersion() {
        return getVersionProps().getProperty("com.puppycrawl.tools:checkstyle");
    }

    @Override
    public void setRewriteVersion(String value) {
        rewriteVersion = value;
    }

    @Override
    public boolean getFailOnInvalidActiveRecipes() {
        return failOnInvalidActiveRecipes;
    }

    @Override
    public void setFailOnInvalidActiveRecipes(boolean failOnInvalidActiveRecipes) {
        this.failOnInvalidActiveRecipes = failOnInvalidActiveRecipes;
    }

    @Override
    public boolean getFailOnDryRunResults() {
        return this.failOnDryRunResults;
    }

    @Override
    public void setFailOnDryRunResults(boolean failOnDryRunResults) {
        this.failOnDryRunResults = failOnDryRunResults;
    }

    @Override
    public boolean getLogCompilationWarningsAndErrors() {
        return logCompilationWarningsAndErrors;
    }

    @Override
    public void setLogCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
        this.logCompilationWarningsAndErrors = logCompilationWarningsAndErrors;
    }

    @Override
    public Provider<File> getCheckstyleConfigProvider() {
        return checkstyleConfigProvider;
    }

    @Override
    public void setCheckstyleConfigProvider(Provider<File> checkstyleConfigProvider) {
        this.checkstyleConfigProvider = checkstyleConfigProvider;
    }

    @Override
    public Provider<Map<String, Object>> getCheckstylePropertiesProvider() {
        return checkstylePropertiesProvider;
    }

    @Override
    public void setCheckstylePropertiesProvider(Provider<Map<String, Object>> checkstylePropertiesProvider) {
        this.checkstylePropertiesProvider = checkstylePropertiesProvider;
    }

    @Override
    public boolean isEnableExperimentalGradleBuildScriptParsing() {
        return enableExperimentalGradleBuildScriptParsing;
    }

    @Override
    public void setEnableExperimentalGradleBuildScriptParsing(boolean enableExperimentalGradleBuildScriptParsing) {
        this.enableExperimentalGradleBuildScriptParsing = enableExperimentalGradleBuildScriptParsing;
    }

    @Override
    public List<String> getExclusions() {
        return exclusions;
    }

    @Override
    public void exclusion(String... exclusions) {
        this.exclusions.addAll(asList(exclusions));
    }

    @Override
    public void exclusion(Collection<String> exclusions) {
        this.exclusions.addAll(exclusions);
    }

    @Override
    public List<String> getPlainTextMasks() {
        if (plainTextMasks.isEmpty()) {
            plainTextMasks.addAll(Arrays.asList(
                    "**gradlew",
                    "**META-INF/services/**",
                    "**/META-INF/spring.factories",
                    "**/META-INF/spring/**",
                    "**.gitignore",
                    "**.gitattributes",
                    "**.java-version",
                    "**.sdkmanrc",
                    "**.sh",
                    "**.bash",
                    "**.bat",
                    "**.ksh",
                    "**.txt",
                    "**.jsp",
                    "**.sql",
                    "**Dockerfile",
                    "**Jenkinsfile",
                    "**/CODEOWNERS",
                    "**.kts"
            ));
        }
        return plainTextMasks;
    }

    @Override
    public void plainTextMask(String... masks) {
        this.plainTextMasks.addAll(asList(masks));
    }

    @Override
    public void plainTextMask(Collection<String> masks) {
        this.plainTextMasks.addAll(masks);
    }

    @Override
    public int getSizeThresholdMb() {
        return sizeThresholdMb;
    }

    @Override
    public void setSizeThresholdMb(int thresholdMb) {
        this.sizeThresholdMb = thresholdMb;
    }

    @Override
    public String getJacksonModuleKotlinVersion() {
        return getVersionProps().getProperty("com.fasterxml.jackson.module:jackson-module-kotlin");
    }

    @Override
    public boolean getThrowOnParseFailures() {
        if(project.getProperties().containsKey("rewrite.throwOnParseFailures")) {
            return true;
        }
        return throwOnParseFailures;
    }

    @Override
    public void setThrowOnParseFailures(boolean throwOnParseFailures) {
        this.throwOnParseFailures = throwOnParseFailures;
    }
}
