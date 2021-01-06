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
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.Change;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RewriteFixTask extends AbstractRewriteTask {
    private static final Logger log = Logging.getLogger(RewriteFixTask.class);

    @Inject
    public RewriteFixTask(SourceSet sourceSet, RewriteExtension extension) {
        super(sourceSet, extension);
        setGroup("rewrite");
        setDescription("Apply the active refactoring recipes to sources within the " + sourceSet.getName() + " SourceSet");
    }

    @Override
    protected Logger getLog() {
        return log;
    }

    @TaskAction
    public void run() {
        ChangesContainer changes = listChanges();

        if (changes.isNotEmpty()) {
            for(Change change : changes.generated) {
                assert change.getFixed() != null;
                getLog().warn("Generated new file " +
                        change.getFixed().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.deleted) {
                assert change.getOriginal() != null;
                getLog().warn("Deleted file " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.moved) {
                assert change.getFixed() != null;
                assert change.getOriginal() != null;
                getLog().warn("File has been moved from " +
                        change.getOriginal().getSourcePath() + " to " +
                        change.getFixed().getSourcePath() + " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.refactoredInPlace) {
                assert change.getOriginal() != null;
                getLog().warn("Changes have been made to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }


            getLog().warn("Please review and commit the changes.");

            try {
                for (Change change : changes.generated) {
                    assert change.getFixed() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            changes.getProjectRoot().resolve(change.getFixed().getSourcePath()))) {
                        sourceFileWriter.write(change.getFixed().print());
                    }
                }
                for (Change change: changes.deleted) {
                    assert change.getOriginal() != null;
                    Path originalLocation = changes.getProjectRoot().resolve(change.getOriginal().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if(!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Change change : changes.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert change.getOriginal() != null;
                    Path originalLocation = changes.getProjectRoot().resolve(change.getOriginal().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if(!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                    assert change.getFixed() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            changes.getProjectRoot().resolve(change.getFixed().getSourcePath()))) {
                        sourceFileWriter.write(change.getFixed().print());
                    }
                }
                for (Change change : changes.refactoredInPlace) {
                    assert change.getOriginal() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            changes.getProjectRoot().resolve(change.getOriginal().getSourcePath()))) {
                        assert change.getFixed() != null;
                        sourceFileWriter.write(change.getFixed().print());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }
}
