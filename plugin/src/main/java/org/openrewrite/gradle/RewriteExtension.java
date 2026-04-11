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

import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;

import javax.inject.Provider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;

/**
 * DSL extension for configuring the OpenRewrite Gradle plugin.
 * <p>
 * Configured via the {@code rewrite} block in a Gradle build script:
 * <pre>
 * rewrite {
 *     activeRecipe("org.openrewrite.java.format.AutoFormat")
 *     exclusion("src/generated/**")
 * }
 * </pre>
 */
@SuppressWarnings("unused")
public class RewriteExtension {

    /**
     * Fully qualified class names of recipes to activate.
     * Recipes will only run when explicitly activated here or in a rewrite.yml file.
     */
    private final List<String> activeRecipes = new ArrayList<>();

    /**
     * Fully qualified class names of styles to activate.
     * Styles will only be applied when explicitly activated here or in a rewrite.yml file.
     */
    private final List<String> activeStyles = new ArrayList<>();
    private boolean configFileSetDeliberately;

    protected final Project project;

    /**
     * Path to the OpenRewrite YAML configuration file.
     * Defaults to {@code rewrite.yml} in the project directory.
     */
    private File configFile;

    @Nullable
    private Provider<File> checkstyleConfigProvider;

    @Nullable
    private Provider<Map<String, Object>> checkstylePropertiesProvider;

    /**
     * Optional path to a Checkstyle configuration file. When set, OpenRewrite will use it
     * to inform Java code style decisions. If not set explicitly, the plugin will attempt
     * to auto-detect a Checkstyle configuration from the Checkstyle Gradle plugin.
     */
    @Nullable
    private File checkstyleConfigFile;

    /**
     * Whether to parse Gradle build scripts ({@code build.gradle}) as part of the source set.
     * Defaults to {@code true}.
     */
    private boolean enableExperimentalGradleBuildScriptParsing = true;

    /**
     * Whether to export data tables to {@code <build directory>/reports/rewrite/datatables/<timestamp>}.
     * Defaults to {@code false}.
     */
    private boolean exportDatatables;

    /**
     * Glob patterns for files to exclude from processing.
     * For example: {@code "src/generated/**"}.
     */
    private final List<String> exclusions = new ArrayList<>();

    /**
     * Glob patterns for files that should be parsed as plain text.
     * Defaults to a comprehensive list including {@code **&#47;*.md}, {@code **&#47;*.sql}, {@code **&#47;*.txt}, and others.
     * Exclusions take precedence over plain text masks.
     */
    private final List<String> plainTextMasks = new ArrayList<>();

    /**
     * Maximum file size in megabytes. Non-Java source files larger than this threshold are skipped during parsing.
     * Defaults to {@code 10}.
     */
    private int sizeThresholdMb = 10;

    /**
     * Override the version of rewrite core libraries to be used.
     * When {@code null}, the version bundled with the plugin is used.
     */
    @Nullable
    private String rewriteVersion;

    @Nullable
    private Properties versionProps;

    /**
     * Whether to log Java compilation warnings and errors encountered during parsing.
     * Defaults to {@code false}.
     */
    private boolean logCompilationWarningsAndErrors;

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    private boolean failOnInvalidActiveRecipes;

    /**
     * Whether {@code rewriteDryRun} should fail the build when it detects that changes would be made.
     * Useful in CI to enforce that all recipes have already been applied.
     * Defaults to {@code false}.
     */
    private boolean failOnDryRunResults;

    /**
     * Whether to throw an exception when source file parsing fails.
     * Can also be enabled via the project property {@code -Prewrite.throwOnParseFailures}.
     * Defaults to {@code false}.
     */
    private boolean throwOnParseFailures;

    @SuppressWarnings("unused")
    public RewriteExtension(Project project) {
        this.project = project;
        configFile = project.file("rewrite.yml");
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
    public @Nullable File getCheckstyleConfigFile() {
        if (checkstyleConfigFile == null && checkstyleConfigProvider != null) {
            try {
                return checkstyleConfigProvider.get();
            } catch (Exception e) {
                return null;
            }
        }
        return checkstyleConfigFile;
    }

    public Map<String, Object> getCheckstyleProperties() {
        if (checkstyleConfigProvider == null) {
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
    public boolean getConfigFileSetDeliberately() {
        return configFileSetDeliberately;
    }

    public File getConfigFile() {
        return configFile;
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
        this.activeStyles.clear();
        this.activeStyles.addAll(activeStyles);
    }

    public List<String> getActiveStyles() {
        return activeStyles;
    }

    public List<String> getActiveRecipes() {
        return activeRecipes;
    }

    public Properties getVersionProps() {
        if (versionProps == null) {
            try (InputStream is = RewriteExtension.class.getResourceAsStream("/rewrite/versions.properties")) {
                versionProps = new Properties();
                versionProps.load(is);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return versionProps;
    }

    /**
     * Returns the version of rewrite core libraries to be used.
     */
    public String getRewriteVersion() {
        if (rewriteVersion == null) {
            return getVersionProps().getProperty("org.openrewrite:rewrite-core");
        }
        return rewriteVersion;
    }

    @Nullable
    private String rewritePolyglotVersion;
    public String getRewritePolyglotVersion() {
        if (rewritePolyglotVersion == null) {
            return getVersionProps().getProperty("org.openrewrite:rewrite-polyglot");
        }
        return rewritePolyglotVersion;
    }

    @Nullable
    private String rewriteGradleModelVersion;
    public String getRewriteGradleModelVersion() {
        if (rewriteGradleModelVersion == null) {
            rewriteGradleModelVersion = getVersionProps().getProperty("org.openrewrite.gradle.tooling:model");
        }
        return rewriteGradleModelVersion;
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

    public Provider<File> getCheckstyleConfigProvider() {
        return checkstyleConfigProvider;
    }

    public void setCheckstyleConfigProvider(Provider<File> checkstyleConfigProvider) {
        this.checkstyleConfigProvider = checkstyleConfigProvider;
    }

    public Provider<Map<String, Object>> getCheckstylePropertiesProvider() {
        return checkstylePropertiesProvider;
    }

    public void setCheckstylePropertiesProvider(Provider<Map<String, Object>> checkstylePropertiesProvider) {
        this.checkstylePropertiesProvider = checkstylePropertiesProvider;
    }

    public boolean isEnableExperimentalGradleBuildScriptParsing() {
        return enableExperimentalGradleBuildScriptParsing;
    }

    public void setEnableExperimentalGradleBuildScriptParsing(boolean enableExperimentalGradleBuildScriptParsing) {
        this.enableExperimentalGradleBuildScriptParsing = enableExperimentalGradleBuildScriptParsing;
    }

    public boolean isExportDatatables() {
        return exportDatatables;
    }

    public void setExportDatatables(boolean exportDatatables) {
        this.exportDatatables = exportDatatables;
    }

    public List<String> getExclusions() {
        return exclusions;
    }

    public void exclusion(String... exclusions) {
        this.exclusions.addAll(asList(exclusions));
    }

    public void exclusion(Collection<String> exclusions) {
        this.exclusions.addAll(exclusions);
    }

    public List<String> getPlainTextMasks() {
        if (plainTextMasks.isEmpty()) {
            plainTextMasks.addAll(Arrays.asList(
                    "**/*.adoc",
                    "**/*.aj",
                    "**/*.bash",
                    "**/*.bat",
                    "**/CODEOWNERS",
                    "**/*.css",
                    "**/*.config",
                    "**/Dockerfile*",
                    "**/*.env",
                    "**/.gitattributes",
                    "**/.gitignore",
                    "**/*.htm*",
                    "**/gradlew",
                    "**/.java-version",
                    "**/*.jelly",
                    "**/*.jsp",
                    "**/*.ksh",
                    "**/*.lock",
                    "**/lombok.config",
                    "**/[mM]akefile",
                    "**/*.md",
                    "**/*.mf",
                    "**/META-INF/services/**",
                    "**/META-INF/spring/**",
                    "**/META-INF/spring.factories",
                    "**/mvnw",
                    "**/*.qute.java",
                    "**/.sdkmanrc",
                    "**/*.sh",
                    "**/*.sql",
                    "**/*.svg",
                    "**/*.tsx",
                    "**/*.txt"
            ));
        }
        return plainTextMasks;
    }

    public void plainTextMask(String... masks) {
        this.plainTextMasks.addAll(asList(masks));
    }

    public void plainTextMask(Collection<String> masks) {
        this.plainTextMasks.addAll(masks);
    }

    public int getSizeThresholdMb() {
        return sizeThresholdMb;
    }

    public void setSizeThresholdMb(int thresholdMb) {
        this.sizeThresholdMb = thresholdMb;
    }

    public String getJacksonModuleKotlinVersion() {
        return getVersionProps().getProperty("com.fasterxml.jackson.module:jackson-module-kotlin");
    }

    public boolean getThrowOnParseFailures() {
        if (project.getProperties().containsKey("rewrite.throwOnParseFailures")) {
            return true;
        }
        return throwOnParseFailures;
    }

    public void setThrowOnParseFailures(boolean throwOnParseFailures) {
        this.throwOnParseFailures = throwOnParseFailures;
    }
}
