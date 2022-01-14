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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Rewrite uses jackson for serialization/deserialization. So do lots of other build plugins.
 * Gradle plugins all share the same classpath at runtime.
 *
 * This classloader exists to isolate rewrite's use of jackson from the rest of the build.
 */
public class RewriteClassLoader extends URLClassLoader {

    private static final List<String> loadFromParent = Arrays.asList(
        "org.openrewrite.config.OptionDescriptor",
        "org.openrewrite.config.RecipeDescriptor",
        "org.openrewrite.gradle.DefaultRewriteExtension",
        "org.openrewrite.gradle.RewriteExtension",
        "org.slf4j",
        "org.gradle"
    );

    public RewriteClassLoader(Collection<URL> artifacts) {
        super(artifacts.toArray(new URL[0]), RewriteClassLoader.class.getClassLoader());
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

        for (String s : loadFromParent) {
            if (name.startsWith(s)) {
                return true;
            }
        }
        return false;
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
