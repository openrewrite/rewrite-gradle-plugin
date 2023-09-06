/*
 * Copyright 2021 the original author or authors.
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

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;

public abstract class RewriteDryRunTask extends AbstractRewriteTask {

    private static final Logger logger = Logging.getLogger(RewriteDryRunTask.class);

    @Inject
    public abstract ProjectLayout getProjectLayout();

    // This must return File, rather than Path.
    // On Gradle 4.0 annotating something returning a Path with @OutputFile triggers a bug that deadlocks Gradle
    @OutputFile
    public File getReportPath() {
        return getProjectLayout()
                .getBuildDirectory()
                .get()
                .getAsFile()
                .toPath()
                .resolve("reports")
                .resolve("rewrite")
                .resolve("rewrite.patch")
                .toFile();
    }

    @Inject
    public RewriteDryRunTask() {
        setGroup("rewrite");
        setDescription("Run the active refactoring recipes, producing a patch file. No source files will be changed.");
        getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @TaskAction
    public void run() {
        getProjectParser().dryRun(getReportPath().toPath(), dumpGcActivity, throwable -> logger.info("Error during rewrite dry run", throwable));
    }
}
