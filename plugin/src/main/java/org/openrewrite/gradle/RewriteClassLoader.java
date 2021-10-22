package org.openrewrite.gradle;

import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Rewrite uses jackson for serialization/deserialization. So do lots of other build plugins.
 * Gradle plugins all share the same classpath at runtime.
 *
 * This classloader exists to isolate rewrite and the version of jackson it uses from the rest of the build.
 */
public class RewriteClassLoader extends URLClassLoader {
    private static final List<String> neverLoadFromParent = Collections.singletonList(
            "org.openrewrite.internal.MetricsHelper"
    );

    private static final List<String> loadFromParent = Arrays.asList(
            "org.openrewrite.Cursor",
            "org.openrewrite.DelegatingExecutionContext",
            "org.openrewrite.ExecutionContext",
            "org.openrewrite.InMemoryExecutionContext",
            "org.openrewrite.Option",
            "org.openrewrite.Recipe",
            "org.openrewrite.Result",
            "org.openrewrite.SourceFile",
            "org.openrewrite.Tree", // and therefore TreePrinter (because it has the same prefix)
            "org.openrewrite.Validated",
            "org.openrewrite.ValidationException",
            "org.openrewrite.config",
            "org.openrewrite.internal",
            "org.openrewrite.marker",
            "org.openrewrite.scheduling",
            "org.openrewrite.style",
            "org.openrewrite.template",
            "org.openrewrite.text"
    );

    public RewriteClassLoader(Collection<Path> artifacts) {
        super(artifacts.stream().map(artifact -> {
            try {
                return artifact.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }).toArray(URL[]::new), RewriteClassLoader.class.getClassLoader());
        setDefaultAssertionStatus(true);
    }

    /**
     * Load the named class. We want classes that extend <code>org.openrewrite.Recipe</code> to be loaded
     * by this ClassLoader <strong>only</strong>. But we want classes required to run recipes to continue
     * to be loaded by their parent ClassLoader to avoid <code>ClassCastException</code>s.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> foundClass = findLoadedClass(name);
        if (foundClass == null) {
            try {
                if (!shouldBeParentLoaded(name)) {
                    foundClass = findClass(name);
                } else {
                    foundClass = super.loadClass(name, resolve);
                }
            } catch (ClassNotFoundException e) {
                foundClass = super.loadClass(name, resolve);
            }
        }
        if (resolve) {
            resolveClass(foundClass);
        }
        return foundClass;
    }

    boolean shouldBeParentLoaded(String name) {
        if (!name.startsWith("org.openrewrite")) {
            return false;
        }

        for (String s : neverLoadFromParent) {
            if (name.startsWith(s)) {
                return false;
            }
        }

        for (String s : loadFromParent) {
            if (name.startsWith(s)) {
                return true;
            }
        }
        return isTreeType(name) || isStyleType(name);
    }

    boolean isTreeType(String name) {
        String[] parts = name.split("\\.");
        return parts.length >= 4 && (parts[3].equals("tree") ||
                // org.openrewrite.java.JavaVisitor has a package tangle with org.openrewrite.java.tree.J
                (parts[3].endsWith("Visitor") && (parts[2] + "visitor").equals(parts[3].toLowerCase()))
        );
    }

    boolean isStyleType(String name) {
        String[] parts = name.split("\\.");
        return parts.length >= 4 && parts[3].equals("style");
    }
}
