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

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import javax.inject.Inject;

public class RewriteRunTask extends AbstractRewriteTask {

    private static final Logger logger = Logging.getLogger(RewriteRunTask.class);

    @Inject
    public RewriteRunTask() {
        setGroup("rewrite");
        setDescription("Apply the active refactoring recipes");
    }

    @Input
    public boolean isUseAstCache() {
        return useAstCache;
    }

    @TaskAction
    public void run() {
        try (DelegatingProjectParser gpp = getProjectParser()) {
            gpp.run(useAstCache, throwable -> logger.warn("Error during rewrite run", throwable));
        }
    }

}
