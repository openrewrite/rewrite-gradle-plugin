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

import org.gradle.internal.service.ServiceRegistry;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface GradleProjectParser {
    List<String> getActiveRecipes();

    List<String> getActiveStyles();

    List<String> getAvailableStyles();

    void discoverRecipes(boolean interactive, ServiceRegistry serviceRegistry);

    Collection<Path> listSources();

    void run(Consumer<Throwable> onError);

    void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError);

    void shutdownRewrite();
}
