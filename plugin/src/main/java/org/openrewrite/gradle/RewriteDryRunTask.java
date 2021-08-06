/*
 * Copyright 2020 the original author or authors.
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

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.gradle.RewriteReflectiveFacade.Result;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class RewriteDryRunTask extends AbstractRewriteTask {
    private static final Logger log = Logging.getLogger(RewriteDryRunTask.class);

    // This @Internal is a lie, the correct annotation here would be @OutputFile
    // On Gradle 4.0 annotating this with @OutputFile triggers a bug that deadlocks Gradle and the task can never begin executing
    @Internal
    Path getReportPath() {
        return getProject().getBuildDir().toPath().resolve("reports").resolve("rewrite").resolve("rewrite.patch");
    }

    @Inject
    public RewriteDryRunTask() {
        setGroup("rewrite");
        setDescription("Dry run the active refactoring recipes. No source files will be changed.");
        getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @Override
    protected Logger getLog() {
        return log;
    }

    @TaskAction
    public void run() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("These recipes would generate new file {}:", result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would delete file {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                getLog().warn("These recipes would move file from {} to {}:", result.getBefore().getSourcePath(), result.getAfter().getSourcePath());
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would make results to {}:", result.getBefore().getSourcePath());
                logRecipesThatMadeChanges(result);
            }

            Path patchFile = getReportPath();
            //noinspection ResultOfMethodCallIgnored
            patchFile.getParent().toFile().mkdirs();
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(
                        Stream.concat(results.generated.stream(), results.deleted.stream()),
                        Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
                )
                        .map(Result::diff)
                        .forEach(diff -> {
                            try {
                                writer.write(diff + "\n");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

            } catch (Exception e) {
                throw new RuntimeException("Unable to generate rewrite result file.", e);
            }
            getLog().warn("Report available:");
            getLog().warn(indent(1, patchFile.normalize().toString()));
            getLog().warn("Run 'gradle rewriteRun' to apply the recipes.");

            if (getExtension().getFailOnDryRunResults()) {
                throw new RuntimeException("Applying recipes would make changes. See logs for more details.");
            }
        } else {
            getLog().lifecycle("Applying recipes would make no changes. No report generated.");
        }
    }
}
