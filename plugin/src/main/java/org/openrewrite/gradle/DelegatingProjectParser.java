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
import org.gradle.internal.service.ServiceRegistry;
import org.jspecify.annotations.Nullable;
import org.openrewrite.gradle.RewritePlugin.ResolvedDependencies;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static java.util.Collections.sort;
import static java.util.stream.Collectors.toList;

public class DelegatingProjectParser implements GradleProjectParser {
    @Nullable
    protected static List<String> rewriteClasspathFingerprint;
    @Nullable
    protected static List<String> recipeClasspathFingerprint;
    @Nullable
    protected static RewriteClassLoader rewriteClassLoader;
    @Nullable
    protected static URLClassLoader recipeClassLoader;
    protected final GradleProjectParser gpp;

    public DelegatingProjectParser(Project project, RewriteExtension extension, ResolvedDependencies classpath) {
        try {
            List<URL> rewriteClasspathUrls = classpath.getFromRewriteOnly().stream()
                    .map(RewritePlugin.ProjectDependency::getUrl)
                    .collect(toList());
            List<URL> recipeClasspathUrls = classpath.getFromRecipeOnly().stream()
                    .map(RewritePlugin.ProjectDependency::getUrl)
                    .collect(toList());

            @SuppressWarnings("ConstantConditions")
            URL currentJar = jarContainingResource(getClass()
                    .getResource("/org/openrewrite/gradle/isolated/DefaultProjectParser.class")
                    .toString());
            rewriteClasspathUrls.add(currentJar);

            List<Path> rewriteClasspathEntries = classpath.getFromRewriteOnly().stream().map(RewritePlugin.ProjectDependency::getPath).collect(toList());
            List<Path> recipeClasspathEntries = classpath.getFromRecipeOnly().stream().map(RewritePlugin.ProjectDependency::getPath).collect(toList());
            rewriteClasspathEntries.add(Paths.get(currentJar.toURI()));

            ClassLoader pluginClassLoader = getPluginClassLoader(project);
            List<String> newRewriteClasspathFingerprint = fingerprint(rewriteClasspathEntries);
            List<String> newRecipeClasspathFingerprint = fingerprint(recipeClasspathEntries);

            // Throw recipe CL if rewrite CL is reset
            // because recipe classes depend on rewrite's dependencies
            if (hasRewriteClasspathChanged(newRewriteClasspathFingerprint, pluginClassLoader)) {
                recreateRewriteClassLoader(rewriteClasspathUrls, newRewriteClasspathFingerprint, pluginClassLoader);
                recreateRecipeClassLoader(recipeClasspathUrls, newRecipeClasspathFingerprint);
            } else if (hasRecipeClasspathChanged(newRecipeClasspathFingerprint)) {
                recreateRecipeClassLoader(recipeClasspathUrls, newRecipeClasspathFingerprint);
            }

            Class<?> gppClass = Class.forName("org.openrewrite.gradle.isolated.DefaultProjectParser", true, recipeClassLoader);
            assert (gppClass.getClassLoader() == rewriteClassLoader) : "DefaultProjectParser must be loaded from RewriteClassLoader to be sufficiently isolated from Gradle's classpath";
            gpp = (GradleProjectParser) gppClass.getDeclaredConstructor(Project.class, RewriteExtension.class, ClassLoader.class)
                    .newInstance(project, extension, new CompositeURLClassLoader(rewriteClassLoader, recipeClassLoader));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasRewriteClasspathChanged(@Nullable List<String> newRewriteClasspathFingerprint, ClassLoader pluginClassLoader) {
        return rewriteClassLoader == null ||
                rewriteClasspathFingerprint == null ||
                !rewriteClasspathFingerprint.equals(newRewriteClasspathFingerprint) ||
                rewriteClassLoader.getPluginClassLoader() != pluginClassLoader;
    }

    private static boolean hasRecipeClasspathChanged(@Nullable List<String> newRecipeClasspathFingerprint) {
        return recipeClassLoader == null ||
                recipeClasspathFingerprint == null ||
                !recipeClasspathFingerprint.equals(newRecipeClasspathFingerprint);
    }

    private static void recreateRewriteClassLoader(List<URL> rewriteClasspathUrls, @Nullable List<String> newRewriteClasspathFingerprint, ClassLoader pluginClassLoader) throws IOException {
        if (rewriteClassLoader != null) {
            discard(rewriteClassLoader);
        }

        rewriteClassLoader = new RewriteClassLoader(rewriteClasspathUrls, pluginClassLoader);
        rewriteClasspathFingerprint = newRewriteClasspathFingerprint;
    }

    private static void recreateRecipeClassLoader(List<URL> recipeClasspathUrls, @Nullable List<String> newRecipeClasspathFingerprint) throws IOException {
        if (recipeClassLoader != null) {
            discard(recipeClassLoader);
        }

        recipeClassLoader = new URLClassLoader(recipeClasspathUrls.toArray(new URL[0]), Objects.requireNonNull(rewriteClassLoader, "Rewrite CL is missing"));
        recipeClasspathFingerprint = newRecipeClasspathFingerprint;
    }

    @Override
    public List<String> getActiveRecipes() {
        return unwrapInvocationException(gpp::getActiveRecipes);
    }

    @Override
    public List<String> getActiveStyles() {
        return unwrapInvocationException(gpp::getActiveStyles);
    }

    @Override
    public List<String> getAvailableStyles() {
        return unwrapInvocationException(gpp::getAvailableStyles);
    }

    @Override
    public void discoverRecipes(ServiceRegistry serviceRegistry) {
        unwrapInvocationException(() -> {
            gpp.discoverRecipes(serviceRegistry);
            return null;
        });
    }

    @Override
    public Collection<Path> listSources() {
        return unwrapInvocationException(gpp::listSources);
    }

    @Override
    public void run(Consumer<Throwable> onError) {
        unwrapInvocationException(() -> {
            gpp.run(onError);
            return null;
        });
    }

    @Override
    public void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError) {
        unwrapInvocationException(() -> {
            gpp.dryRun(reportPath, dumpGcActivity, onError);
            return null;
        });
    }

    @Override
    public void shutdownRewrite() {
        unwrapInvocationException(() -> {
            gpp.shutdownRewrite();
            return null;
        });
    }

    private static void discard(URLClassLoader classLoader) throws IOException {
        try {
            Class.forName("org.openrewrite.gradle.isolated.DefaultProjectParser", true, classLoader)
                    .getMethod("shutdownJGitWorkQueue")
                    .invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Not all versions of rewrite bundle JGit, in which case there is no work queue to shut down
        }
        classLoader.close();
    }

    /**
     * Recipe jars built by the project itself are replaced in place, keeping the same location on the classpath.
     * Comparing locations alone would then reuse a {@link RewriteClassLoader} holding the previous recipe classes
     * for as long as the Gradle daemon lives, so compare the contents of each classpath entry as well.
     *
     * @return a fingerprint per classpath entry, or {@code null} if any entry could not be read
     */
    static @Nullable List<String> fingerprint(Collection<Path> classpath) {
        List<String> fingerprints = new ArrayList<>(classpath.size());
        for (Path classpathEntry : classpath) {
            try {
                fingerprints.add(fingerprint(classpathEntry));
            } catch (IOException e) {
                return null;
            }
        }
        sort(fingerprints);
        return fingerprints;
    }

    private static String fingerprint(Path classpathEntry) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(classpathEntry, BasicFileAttributes.class);
        if (!attributes.isDirectory()) {
            return classpathEntry + "|" + stamp(classpathEntry, attributes);
        }
        DirectoryStamp directoryStamp = new DirectoryStamp();
        Files.walkFileTree(classpathEntry, directoryStamp);
        return classpathEntry + "|" + directoryStamp.stamp;
    }

    private static long stamp(Path file, BasicFileAttributes attributes) {
        return 31L * (31L * file.hashCode() + attributes.size()) + attributes.lastModifiedTime().toMillis();
    }

    private static class DirectoryStamp extends SimpleFileVisitor<Path> {
        private long stamp;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            stamp += stamp(file, attributes);
            return FileVisitResult.CONTINUE;
        }
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
            if (resourcePath.startsWith("file:")) {
                return new URI(resourcePath.substring(0, resourcePath.lastIndexOf("/main/") + 6)).toURL();
            }
            // This code path only gets taken when running the tests against older versions of Gradle
            // In all other circumstances, "path" will point at a jar file
            return Paths.get(System.getProperty("jarLocationForTest")).toUri().toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Bloating stack traces with reflection errors isn't generally helpful for understanding what went wrong.
     * <p>
     * This highlights the actual cause of a problem, allowing Gradle's console to display something useful like
     * "Recipe validation errors detected ..." rather than only "InvocationTargetException ..."
     */
    private <T> T unwrapInvocationException(Callable<T> supplier) {
        try {
            return supplier.call();
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            }
            throw new RuntimeException(e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ClassLoader getPluginClassLoader(Project project) {
        ClassLoader pluginClassLoader = getAndroidPluginClassLoader(project);
        if (pluginClassLoader == null) {
            pluginClassLoader = getClass().getClassLoader();
        }
        return pluginClassLoader;
    }

    private @Nullable ClassLoader getAndroidPluginClassLoader(Project project) {
        List<String> pluginIds = Arrays.asList(
                "com.android.application",
                "com.android.library",
                "com.android.feature",
                "com.android.dynamic-feature",
                "com.android.test");

        for (String pluginId : pluginIds) {
            if (project.getPlugins().hasPlugin(pluginId)) {
                Object plugin = project.getPlugins().getPlugin(pluginId);
                return plugin.getClass().getClassLoader();
            }
        }
        return null;
    }
}
