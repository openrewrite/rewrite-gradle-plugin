/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
 */
package org.openrewrite.gradle;

import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;

public class RewriteDiscoverTask extends AbstractRewriteTask {

    @Inject
    public RewriteDiscoverTask() {
        setGroup("rewrite");
        setDescription("Lists all available recipes and their visitors");
    }

    @TaskAction
    public void run() {
        getProjectParser().discoverRecipes(getServices());
    }
}
