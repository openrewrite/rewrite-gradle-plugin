package org.openrewrite.gradle;

import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    int getSizeThresholdMb();

    void setSizeThresholdMb(int thresholdMb);
}
