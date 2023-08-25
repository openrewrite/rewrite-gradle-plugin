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


import org.jetbrains.annotations.Nullable;

import javax.inject.Provider;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * If the OSS plugin is applied but comes from a different classloader than this plugin
 * then there would be ClassCastException trying to access the existing rewrite DSL.
 * So use reflective access to avoid these classloading difficulties.
 * <p>
 * The one scenario where this is known to be problematic is when using the CLI on a build which applies the OSS plugin
 */
@SuppressWarnings({"unchecked", "unused"})
public class DelegatingRewriteExtension implements RewriteExtension {
    final Object delegate;
    final Class<?> clazz;

    public DelegatingRewriteExtension(Object delegate) {
        this.delegate = delegate;
        this.clazz = delegate.getClass();
    }

    @Override
    public void setConfigFile(File configFile) {
        try {
            clazz.getMethod("setConfigFile", File.class)
                    .invoke(delegate, configFile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setConfigFile(String configFilePath) {
        try {
            clazz.getMethod("setConfigFile", String.class)
                    .invoke(delegate, configFilePath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setCheckstyleConfigFile(File configFile) {
        try {
            clazz.getMethod("setCheckstyleConfigFile", File.class)
                    .invoke(delegate, configFile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public File getCheckstyleConfigFile() {
        try {
            return (File) clazz.getMethod("getCheckstyleConfigFile")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Object> getCheckstyleProperties() {
        try {
            return (Map<String, Object>) clazz.getMethod("getCheckstyleProperties")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getConfigFileSetDeliberately() {
        try {
            return (boolean) clazz.getMethod("getConfigFileSetDeliberately")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public File getConfigFile() {
        try {
            return (File) clazz.getMethod("getConfigFile")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void enableRouteMetricsToLog() {
        try {
            clazz.getMethod("enableRouteMetricsToLog")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isRouteMetricsToLog() {
        try {
            return (boolean) clazz.getMethod("isRouteMetricsToLog")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getMetricsUri() {
        try {
            return (String) clazz.getMethod("getMetricsUri")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setMetricsUri(String value) {
        try {
            clazz.getMethod("setMetricsUri", String.class)
                    .invoke(delegate, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void activeRecipe(String... recipes) {
        try {
            clazz.getMethod("activeRecipe", String[].class)
                    .invoke(delegate, (Object) recipes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clearActiveRecipes() {
        try {
            clazz.getMethod("clearActiveRecipes")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setActiveRecipes(List<String> activeRecipes) {
        try {
            clazz.getMethod("setActiveRecipes", List.class)
                    .invoke(delegate, activeRecipes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void activeStyle(String... styles) {
        try {
            clazz.getMethod("activeStyle", String[].class)
                    .invoke(delegate, (Object) styles);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clearActiveStyles() {
        try {
            clazz.getMethod("clearActiveStyles")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setActiveStyles(List<String> activeStyles) {
        try {
            clazz.getMethod("setActiveStyles", List.class)
                    .invoke(delegate, activeStyles);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getActiveStyles() {
        try {
            return (List<String>) clazz.getMethod("getActiveStyles")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getActiveRecipes() {
        try {
            return (List<String>) clazz.getMethod("getActiveRecipes")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRewriteVersion() {
        try {
            return (String) clazz.getMethod("getRewriteVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRewriteAllVersion() {
        try {
            return (String) clazz.getMethod("getRewriteAllVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRewriteGradleModelVersion() {
        try {
            return (String) clazz.getMethod("getRewriteGradleModelVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRewriteKotlinVersion() {
        try {
            return (String) clazz.getMethod("getRewriteKotlinVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getCheckstyleToolsVersion() {
        try {
            return (String) clazz.getMethod("getCheckstyleToolsVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setRewriteVersion(String value) {
        try {
            clazz.getMethod("setRewriteVersion", String.class)
                    .invoke(delegate, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getFailOnInvalidActiveRecipes() {
        try {
            return (boolean) clazz.getMethod("getFailOnInvalidActiveRecipes")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setFailOnInvalidActiveRecipes(boolean failOnInvalidActiveRecipes) {
        try {
            clazz.getMethod("setFailOnInvalidActiveRecipes", boolean.class)
                    .invoke(delegate, failOnInvalidActiveRecipes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getFailOnDryRunResults() {
        try {
            return (boolean) clazz.getMethod("getFailOnDryRunResults")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setFailOnDryRunResults(boolean failOnDryRunResults) {
        try {
            clazz.getMethod("setFailOnDryRunResults", boolean.class)
                    .invoke(delegate, failOnDryRunResults);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getLogCompilationWarningsAndErrors() {
        try {
            return (boolean) clazz.getMethod("getLogCompilationWarningsAndErrors")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setLogCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
        try {
            clazz.getMethod("setLogCompilationWarningsAndErrors", boolean.class)
                    .invoke(delegate, logCompilationWarningsAndErrors);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Provider<File> getCheckstyleConfigProvider() {
        try {
            return (Provider<File>) clazz.getMethod("getCheckstyleConfigProvider")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setCheckstyleConfigProvider(Provider<File> checkstyleConfigProvider) {
        try {
            clazz.getMethod("setCheckstyleConfigProvider", Provider.class)
                    .invoke(delegate, checkstyleConfigProvider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Provider<Map<String, Object>> getCheckstylePropertiesProvider() {
        try {
            return (Provider<Map<String, Object>>) clazz.getMethod("getCheckstylePropertiesProvider")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setCheckstylePropertiesProvider(Provider<Map<String, Object>> checkstylePropertiesProvider) {
        try {
            clazz.getMethod("setCheckstylePropertiesProvider", Provider.class)
                    .invoke(delegate, checkstylePropertiesProvider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isEnableExperimentalGradleBuildScriptParsing() {
        try {
            return (boolean) clazz.getMethod("isEnableExperimentalGradleBuildScriptParsing")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setEnableExperimentalGradleBuildScriptParsing(boolean enableExperimentalGradleBuildScriptParsing) {
        try {
            clazz.getMethod("setEnableExperimentalGradleBuildScriptParsing", boolean.class)
                    .invoke(delegate, enableExperimentalGradleBuildScriptParsing);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getExclusions() {
        try {
            return (List<String>) clazz.getMethod("getExclusions")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void exclusion(String... exclusions) {
        try {
            clazz.getMethod("exclusion", String[].class)
                    .invoke(delegate, (Object[]) exclusions);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void exclusion(Collection<String> exclusions) {
        try {
            clazz.getMethod("exclusion", Collection.class)
                    .invoke(delegate, exclusions);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getPlainTextMasks() {
        try {
            return (List<String>) clazz.getMethod("getPlainTextMasks")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void plainTextMask(String... masks) {
        try {
            clazz.getMethod("plainTextMask", String[].class)
                    .invoke(delegate, (Object[]) masks);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void plainTextMask(Collection<String> masks) {
        try {
            clazz.getMethod("plainTextMask", Collection.class)
                    .invoke(delegate, masks);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getSizeThresholdMb() {
        try {
            return (int) clazz.getMethod("getSizeThresholdMb")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setSizeThresholdMb(int thresholdMb) {
        try {
            clazz.getMethod("setSizeThresholdMb", int.class)
                    .invoke(delegate, thresholdMb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getJacksonModuleKotlinVersion() {
        try {
            return (String) clazz.getMethod("getJacksonModuleKotlinVersion")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean getThrowOnParseFailures() {
        try {
            return (boolean) clazz.getMethod("getThrowOnParseFailures")
                    .invoke(delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setThrowOnParseFailures(boolean throwOnParseFailures) {
        try {
            clazz.getMethod("setThrowOnParseFailures", boolean.class)
                    .invoke(delegate, throwOnParseFailures);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
