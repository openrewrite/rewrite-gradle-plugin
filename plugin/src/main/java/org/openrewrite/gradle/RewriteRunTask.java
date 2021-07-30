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

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.gradle.RewriteReflectiveFacade.*;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

public class RewriteRunTask extends AbstractRewriteTask {
    private static final Logger log = Logging.getLogger(RewriteRunTask.class);

    @Inject
    public RewriteRunTask(Configuration configuration, Collection<SourceSet> sourceSets, RewriteExtension extension) {
        super(configuration, sourceSets, extension);
        setGroup("rewrite");
        setDescription("Apply the active refactoring recipes");
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
                getLog().lifecycle("Generated new file " +
                        result.getAfter().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().lifecycle("Deleted file " +
                        result.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                getLog().lifecycle("File has been moved from " +
                        result.getBefore().getSourcePath() + " to " +
                        result.getAfter().getSourcePath() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().lifecycle("Changes have been made to " +
                        result.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }

            getLog().lifecycle("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getAfter().getSourcePath()))) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    /*boolean deleteSucceeded = */originalLocation.toFile().delete();
//                    if (!deleteSucceeded) {
//                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
//                    }
                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File parentDir = afterLocation.toFile().getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parentDir.mkdirs();
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                        assert result.getAfter() != null;
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }
}
