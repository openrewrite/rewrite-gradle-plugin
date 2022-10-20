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
import org.gradle.internal.service.ServiceRegistry;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DelegatingProjectParser implements GradleProjectParser {
    protected final GradleProjectParser gpp;
    protected static List<URL> rewriteClasspath;
    protected static RewriteClassLoader rewriteClassLoader;

    public DelegatingProjectParser(Project project, RewriteExtension extension, Set<Path> classpath) {
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
            URL currentJar = jarContainingResource(getClass()
                    .getResource("/org/openrewrite/gradle/isolated/DefaultProjectParser.class")
                    .toString());
            classpathUrls.add(currentJar);
            if(rewriteClassLoader == null || !classpathUrls.equals(rewriteClasspath)) {
                if (rewriteClassLoader != null) {
                    rewriteClassLoader.close();
                }
                rewriteClassLoader = new RewriteClassLoader(classpathUrls);
                rewriteClasspath = classpathUrls;
            }

            Class<?> gppClass = Class.forName("org.openrewrite.gradle.isolated.DefaultProjectParser", true, rewriteClassLoader);
            assert (gppClass.getClassLoader() == rewriteClassLoader) : "DefaultProjectParser must be loaded from RewriteClassLoader to be sufficiently isolated from Gradle's classpath";
            gpp = (GradleProjectParser) gppClass.getDeclaredConstructor(Project.class, RewriteExtension.class)
                    .newInstance(project, extension);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SortedSet<String> getActiveRecipes() {
        return unwrapInvocationException(gpp::getActiveRecipes);
    }

    public SortedSet<String> getActiveStyles() {
        return unwrapInvocationException(gpp::getActiveStyles);
    }

    public SortedSet<String> getAvailableStyles() {
        return unwrapInvocationException(gpp::getAvailableStyles);
    }

    public void discoverRecipes(boolean interactive, ServiceRegistry serviceRegistry) {
        unwrapInvocationException(() -> {
            gpp.discoverRecipes(interactive, serviceRegistry);
            return null;
        });
    }

    public Collection<Path> listSources() {
        return unwrapInvocationException(gpp::listSources);
    }

    public void run(Consumer<Throwable> onError) {
        unwrapInvocationException(() -> {
            gpp.run(onError);
            return null;
        });
    }

    public void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError) {
        unwrapInvocationException(() -> {
            gpp.dryRun(reportPath, dumpGcActivity, onError);
            return null;
        });
    }

    public void shutdownRewrite() {
        unwrapInvocationException(() -> {
            gpp.shutdownRewrite();
            return null;
        });
    }

    protected URL jarContainingResource(String resourcePath) {
        try {
            if (resourcePath.startsWith("jar:")) {
                resourcePath = resourcePath.substring(4);
                int indexOfBang = resourcePath.indexOf("!");
                if (indexOfBang != -1) {
                    resourcePath = resourcePath.substring(0, indexOfBang);
                }
                return new URI(resourcePath).toURL();
            }
            // This code path only gets taken when running the tests against older versions of Gradle
            // In all other circumstances, "path" will point at a jar file
            return Paths.get(System.getProperty("jarLocationForTest")).toUri().toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            } else {
                throw new RuntimeException(e.getTargetException());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
