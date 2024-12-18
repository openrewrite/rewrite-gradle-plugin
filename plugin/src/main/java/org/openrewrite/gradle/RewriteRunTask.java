/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
 */
package org.openrewrite.gradle;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;

public class RewriteRunTask extends AbstractRewriteTask {

    private static final Logger logger = Logging.getLogger(RewriteRunTask.class);

    @Inject
    public RewriteRunTask() {
        setGroup("rewrite");
        setDescription("Apply the active refactoring recipes");
    }

    @TaskAction
    public void run() {
        getProjectParser().run(throwable -> logger.info("Error during rewrite run", throwable));
    }

}
