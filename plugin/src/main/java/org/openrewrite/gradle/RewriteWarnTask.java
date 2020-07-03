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
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.Change;
import org.openrewrite.java.tree.J;

import java.util.List;

public class RewriteWarnTask extends AbstractRewriteTask{
    private static final Logger log = Logging.getLogger(RewriteWarnTask.class);

    @Override
    protected Logger getLog() {
        return log;
    }

    @TaskAction
    public void execute() {
        List<Change<J.CompilationUnit>> changes = listChanges();

        if (!changes.isEmpty()) {
            for (Change<J.CompilationUnit> change : changes) {
                log.warn("Changes are suggested to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                for (String rule : change.getRulesThatMadeChanges()) {
                    log.warn("   " + rule);
                }
            }

            log.warn("Run rewriteFix to apply the fixes. Afterwards, review and commit the changes.");
        }
    }
}
