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

import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import javax.inject.Inject;
import java.nio.file.Path;

public class RewriteDryRunTask extends AbstractRewriteTask {

    // This @Internal is a lie, the correct annotation here would be @OutputFile
    // On Gradle 4.0 annotating this with @OutputFile triggers a bug that deadlocks Gradle and the task can never begin executing
    @Internal
    Path getReportPath() {
        return getProject().getBuildDir().toPath().resolve("reports").resolve("rewrite").resolve("rewrite.patch");
    }

    @Inject
    public RewriteDryRunTask() {
        setGroup("rewrite");
        setDescription("Run the active refactoring recipes, producing a patch file. No source files will be changed.");
        getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @Option(description = "Cache the AST results in-memory when using the Gradle daemon.", option = "useAstCache")
    public void setUseAstCache(boolean useAstCache) {
        this.useAstCache = useAstCache;
    }

    @Input
    public boolean isUseAstCache() {
        return useAstCache;
    }

    @TaskAction
    public void run() {
        getProjectParser().rewriteDryRun(getReportPath());
    }
}
