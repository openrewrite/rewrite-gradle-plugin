/*
 * Copyright 2023 the original author or authors.
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

import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public interface RewriteExtension {
    void setConfigFile(File configFile);

    void setConfigFile(String configFilePath);

    void setCheckstyleConfigFile(File configFile);

    @Nullable
    File getCheckstyleConfigFile();

    Map<String, Object> getCheckstyleProperties();

    boolean getConfigFileSetDeliberately();

    File getConfigFile();

    void enableRouteMetricsToLog();

    boolean isRouteMetricsToLog();

    String getMetricsUri();

    void setMetricsUri(String value);

    void activeRecipe(String... recipes);

    void clearActiveRecipes();

    void setActiveRecipes(List<String> activeRecipes);

    void activeStyle(String... styles);

    void clearActiveStyles();

    void setActiveStyles(List<String> activeStyles);

    List<String> getActiveStyles();

    List<String> getActiveRecipes();

    String getRewriteVersion();

    String getRewriteAllVersion();

    String getRewriteGradleModelVersion();

    String getRewriteKotlinVersion();

    String getCheckstyleToolsVersion();

    void setRewriteVersion(String value);

    boolean getFailOnInvalidActiveRecipes();

    void setFailOnInvalidActiveRecipes(boolean failOnInvalidActiveRecipes);

    boolean getFailOnDryRunResults();

    void setFailOnDryRunResults(boolean failOnDryRunResults);

    boolean getLogCompilationWarningsAndErrors();

    void setLogCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors);

    Provider<File> getCheckstyleConfigProvider();

    void setCheckstyleConfigProvider(Provider<File> checkstyleConfigProvider);

    Provider<Map<String, Object>> getCheckstylePropertiesProvider();

    void setCheckstylePropertiesProvider(Provider<Map<String, Object>> checkstylePropertiesProvider);

    boolean isEnableExperimentalGradleBuildScriptParsing();

    void setEnableExperimentalGradleBuildScriptParsing(boolean enableExperimentalGradleBuildScriptParsing);

    List<String> getExclusions();

    void exclusion(String... exclusions);

    void exclusion(Collection<String> exclusions);

    List<String> getPlainTextMasks();

    void plainTextMask(String... masks);

    void plainTextMask(Collection<String> masks);

    int getSizeThresholdMb();

    void setSizeThresholdMb(int thresholdMb);

    String getJacksonModuleKotlinVersion();

    boolean getThrowOnParseFailures();

    void setThrowOnParseFailures(boolean throwOnParseFailures);
}
