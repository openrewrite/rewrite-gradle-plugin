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

public class RewriteWarnTask extends AbstractRewriteTask{
    private static final Logger log = Logging.getLogger(RewriteWarnTask.class);

    @Inject
    public RewriteWarnTask(SourceSet sourceSet, RewriteExtension extension) {
        super(sourceSet, extension);
        setGroup("rewrite");
        setDescription("Dry run the active refactoring recipes to sources within the " + sourceSet.getName() + " SourceSet. No changes will be made.");
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
                getLog().warn("Applying fixes would generate new file " +
                        change.getFixed().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.deleted) {
                assert change.getOriginal() != null;
                getLog().warn("Applying fixes would delete file " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.moved) {
                assert change.getOriginal() != null;
                assert change.getFixed() != null;
                getLog().warn("Applying fixes would move file from " +
                        change.getOriginal().getSourcePath() + " to " +
                        change.getFixed().getSourcePath() + " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.refactoredInPlace) {
                assert change.getOriginal() != null;
                getLog().warn("Applying fixes would make changes to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            getLog().warn("Run 'gradle rewriteFix' to apply the fixes. Afterwards, review and commit the changes.");
        }
    }
}
