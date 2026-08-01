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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * Class loader passed to {@code rewrite-core}'s {@link org.openrewrite.config.Environment Environment}
 * so it can discover recipes from multiple class loaders.
 * In our case, they are from {@code RewritePlugin.knownRewriteDependencies} and recipes in the {@code rewrite} configuration.
 */
public class CompositeURLClassLoader extends URLClassLoader {

    private final Collection<URLClassLoader> loaders;

    private CompositeURLClassLoader(Collection<URLClassLoader> loaders) {
        super(loaders.stream().flatMap(cl -> Arrays.stream(cl.getURLs())).toArray(URL[]::new));
        this.loaders = loaders;
    }

    public CompositeURLClassLoader(URLClassLoader... loaders) {
        this(new ArrayList<>(Arrays.asList(loaders)));
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (URLClassLoader loader : loaders) {
            try {
                return loader.loadClass(name);
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }

        throw new ClassNotFoundException(name);
    }
}
