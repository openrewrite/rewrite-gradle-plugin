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
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.gradle.AbstractRewriteTask.ResultsContainer;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class DelegatingProjectParser {
    private final Class<?> gppClass;
    private final Object gpp;

    public DelegatingProjectParser(Project rootProject, Set<Path> classpath, boolean useAstCache) {
        try {
            List<URL> classpathUrls = classpath.stream()
                    .map(Path::toUri)
                    .map(uri -> {
                        try {
                            return uri.toURL();
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
            @SuppressWarnings("ConstantConditions")
            String path = getClass()
                    .getResource("/org/openrewrite/gradle/GradleProjectParser.class")
                    .toString();
            URL currentJar = null;
            if(path.startsWith("jar:")) {
                path = path.substring(4);
                int indexOfBang = path.indexOf("!");
                if(indexOfBang != -1) {
                    path = path.substring(0, indexOfBang);
                }
                currentJar = new URI(path).toURL();
            } else if(path.endsWith(".class")) {
                // This code path only gets taken when running the tests against older versions of Gradle
                // In all other circumstances, "path" will point at a jar file
                currentJar = Paths.get(System.getProperty("jarLocationForTest")).toUri().toURL();
            }

            classpathUrls.add(currentJar);
            RewriteClassLoader cl = new RewriteClassLoader(classpathUrls);

            gppClass = Class.forName("org.openrewrite.gradle.GradleProjectParser", true, cl);
            assert (gppClass.getClassLoader() == cl) : "GradleProjectParser must be loaded from RewriteClassLoader to be sufficiently isolated from Gradle's own classpath";
            gpp = gppClass.getDeclaredConstructor(Project.class, Boolean.class)
                    .newInstance(rootProject, useAstCache);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SortedSet<String> getActiveRecipes() {
        return unwrapInvocationException(() -> (SortedSet<String>) gppClass.getMethod("getActiveRecipes").invoke(gpp));
    }

    public SortedSet<String> getActiveStyles() {
        return unwrapInvocationException(() -> (SortedSet<String>) gppClass.getMethod("getActiveStyles").invoke(gpp));
    }

    public Environment environment() {
        return unwrapInvocationException(() -> (Environment) gppClass.getMethod("environment").invoke(gpp));
    }

    @SuppressWarnings("unused")
    public List<SourceFile> parse() {
        return unwrapInvocationException(() -> (List<SourceFile>) gppClass.getMethod("parse").invoke(gpp));
    }

    public ResultsContainer listResults() {
        return unwrapInvocationException(() -> (ResultsContainer) gppClass.getMethod("listResults").invoke(gpp));
    }

    public void clearAstCache() {
        unwrapInvocationException(() -> gppClass.getMethod("clearAstCache").invoke(gpp));
    }

    /**
     * Bloating stacktraces with reflection errors isn't generally helpful for understanding what went wrong.
     *
     * This highlights the actual cause of a problem, allowing Gradle's console to display something useful like
     * "Recipe validation errors detected ..." rather than only "InvocationTargetException ..."
     */
    private <T> T unwrapInvocationException(Callable<T> supplier) {
        try {
            return supplier.call();
        } catch (InvocationTargetException e) {
            if(e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            } else {
                throw new RuntimeException(e.getTargetException());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
