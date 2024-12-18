/*
 * Licensed under the Moderne Source Available License.
 * See https://docs.moderne.io/licensing/moderne-source-available-license
 */
package org.openrewrite.gradle;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.nio.file.Path;

public class RewriteDryRunTask extends AbstractRewriteTask {

    private static final Logger logger = Logging.getLogger(RewriteDryRunTask.class);

    @OutputFile
    public Path getReportPath() {
        return getProjectLayout()
                .getBuildDirectory()
                .get()
                .getAsFile()
                .toPath()
                .resolve("reports")
                .resolve("rewrite")
                .resolve("rewrite.patch");
    }

    @Inject
    public RewriteDryRunTask() {
        setGroup("rewrite");
        setDescription("Run the active refactoring recipes, producing a patch file. No source files will be changed.");
        getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @TaskAction
    public void run() {
        getProjectParser().dryRun(getReportPath(), dumpGcActivity, throwable -> logger.info("Error during rewrite dry run", throwable));
    }
}
