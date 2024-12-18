/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
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

    Collection<Path> listSources();

    void discoverRecipes(ServiceRegistry serviceRegistry);

    /**
     * @deprecated Use {@link #discoverRecipes(ServiceRegistry)} instead.
     */
    @Deprecated
    default void discoverRecipes(boolean interactive, ServiceRegistry serviceRegistry) {
        discoverRecipes(serviceRegistry);
    }

    void run(Consumer<Throwable> onError);

    void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError);

    void shutdownRewrite();
}
