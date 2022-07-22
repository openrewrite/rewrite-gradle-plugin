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
import org.openrewrite.config.RecipeDescriptor;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class DelegatingProjectParser implements GradleProjectParser, Closeable {
    protected final Class<?> gppClass;
    protected final Object gpp;
    protected static List<URL> rewriteClasspath;
    protected static RewriteClassLoader rewriteClassLoader;
    protected static final Map<String, Object> astCache = new HashMap<>();

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
            String path = getClass()
                    .getResource("/org/openrewrite/gradle/isolated/DefaultProjectParser.class")
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
            if(rewriteClassLoader == null || !classpathUrls.equals(rewriteClasspath)) {
                rewriteClassLoader = new RewriteClassLoader(classpathUrls);
                rewriteClasspath = classpathUrls;
                astCache.clear();
            }

            gppClass = Class.forName("org.openrewrite.gradle.isolated.DefaultProjectParser", true, rewriteClassLoader);
            assert (gppClass.getClassLoader() == rewriteClassLoader) : "DefaultProjectParser must be loaded from RewriteClassLoader to be sufficiently isolated from Gradle's classpath";
            gpp = gppClass.getDeclaredConstructor(Project.class, RewriteExtension.class, Map.class)
                    .newInstance(project, extension, astCache);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SortedSet<String> getActiveRecipes() {
        return unwrapInvocationException(() -> (SortedSet<String>) gppClass.getMethod("getActiveRecipes").invoke(gpp));
    }

    @Override
    public SortedSet<String> getActiveStyles() {
        return unwrapInvocationException(() -> (SortedSet<String>) gppClass.getMethod("getActiveStyles").invoke(gpp));
    }

    @Override
    public SortedSet<String> getAvailableStyles() {
        return unwrapInvocationException(() -> (SortedSet<String>) gppClass.getMethod("getAvailableStyles").invoke(gpp));
    }

    @Override
    public Collection<RecipeDescriptor> listRecipeDescriptors() {
        return unwrapInvocationException(() -> (Collection<RecipeDescriptor>) gppClass.getMethod("listRecipeDescriptors").invoke(gpp));
    }

    @Override
    public Collection<Path> listSources(Project project) {
        return unwrapInvocationException(() -> (Collection<Path>) gppClass.getMethod("listSources").invoke(gpp));
    }

    @Override
    public void run(boolean useAstCache, Consumer<Throwable> onError) {
        unwrapInvocationException(() -> gppClass.getMethod("run", boolean.class, Consumer.class).invoke(gpp, useAstCache, onError));
    }

    @Override
    public void dryRun(Path reportPath, boolean dumpGcActivity, boolean useAstCache, Consumer<Throwable> onError) {
        unwrapInvocationException(() -> gppClass.getMethod("dryRun", Path.class, boolean.class, boolean.class, Consumer.class).invoke(gpp, reportPath, dumpGcActivity, useAstCache, onError));
    }

    @Override
    public void clearAstCache() {
        unwrapInvocationException(() -> gppClass.getMethod("clearAstCache").invoke(gpp));
    }

    @Override
    public void shutdownRewrite() {
        unwrapInvocationException(() -> gppClass.getMethod("shutdownRewrite").invoke(gpp));
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

    @Override
    public void close(){
        if(rewriteClassLoader != null) {
            try {
                rewriteClassLoader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
