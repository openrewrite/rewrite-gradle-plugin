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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class DelegatingProjectParser implements GradleProjectParser {
    @Nullable
    protected static ClasspathSnapshot classpathSnapshot;
    @Nullable
    protected static RewriteClassLoader rewriteClassLoader;
    protected final GradleProjectParser gpp;

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
                    .collect(toList());

            @SuppressWarnings("ConstantConditions")
            URL currentJar = jarContainingResource(getClass()
                    .getResource("/org/openrewrite/gradle/isolated/DefaultProjectParser.class")
                    .toString());
            classpathUrls.add(currentJar);

            ClassLoader pluginClassLoader = getPluginClassLoader(project);

            if (rewriteClassLoader == null ||
                    classpathSnapshot == null ||
                    classpathSnapshot.hasChanged(classpathUrls) ||
                    rewriteClassLoader.getPluginClassLoader() != pluginClassLoader) {
                if (rewriteClassLoader != null) {
                    rewriteClassLoader.close();
                }
                rewriteClassLoader = new RewriteClassLoader(classpathUrls, pluginClassLoader);
                classpathSnapshot = new ClasspathSnapshot(classpathUrls);
            }

            Class<?> gppClass = Class.forName("org.openrewrite.gradle.isolated.DefaultProjectParser", true, rewriteClassLoader);
            assert (gppClass.getClassLoader() == rewriteClassLoader) : "DefaultProjectParser must be loaded from RewriteClassLoader to be sufficiently isolated from Gradle's classpath";
            gpp = (GradleProjectParser) gppClass.getDeclaredConstructor(Project.class, RewriteExtension.class)
                    .newInstance(project, extension);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    protected static class ClasspathSnapshot {
        private final Map<Path, Item> items = new HashMap<>();

        protected ClasspathSnapshot(Collection<URL> urls) throws IOException, NoSuchAlgorithmException {
            for (Path path : getJars(urls)) {
                items.put(path, new Item(path));
            }
        }

        protected boolean hasChanged(Collection<URL> urls) throws IOException, NoSuchAlgorithmException {
            Set<Path> jars = getJars(urls);

            // Check we have all paths
            if (!items.keySet().equals(jars)) return true;

            for (Path path : jars) {
                Item oldItem = items.get(path);
                // Just in case
                if (oldItem == null) return true;

                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.lastModifiedTime().equals(oldItem.lastModified)) return true;
                if (attributes.size() != oldItem.size) return true;

                byte[] sha256 = Item.computeHash(path);
                if (!Arrays.equals(sha256, oldItem.sha256)) return true;
            }

            return false;
        }

        private static Set<Path> getJars(Collection<URL> urls) {
            return urls.stream()
                    .map(url -> {
                        try {
                            return Paths.get(url.toURI());
                        } catch (URISyntaxException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(p -> p.toString().endsWith(".jar"))
                    .collect(toSet());
        }

        private static class Item {
            private final FileTime lastModified;
            private final long size;
            private final byte[] sha256;

            private Item(Path path) throws IOException, NoSuchAlgorithmException {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                this.lastModified = attributes.lastModifiedTime();
                this.size = attributes.size();
                this.sha256 = computeHash(path);
            }

            private static byte[] computeHash(Path path) throws NoSuchAlgorithmException, IOException {
                return MessageDigest.getInstance("SHA256").digest(Files.readAllBytes(path));
            }
        }
    }
}
