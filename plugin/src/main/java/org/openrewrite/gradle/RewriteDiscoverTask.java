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

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import javax.inject.Inject;

public abstract class RewriteDiscoverTask extends AbstractRewriteTask {
    private boolean interactive;

    @Option(description = "Whether to enter an interactive shell to explore available recipes.", option = "interactive")
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    @Input
    public boolean isInteractive() {
        return this.interactive;
    }

    @Inject
    public RewriteDiscoverTask() {
        setGroup("rewrite");
        setDescription("Lists all available recipes and their visitors");
    }

    @TaskAction
    public void run() {
        getProjectParser().discoverRecipes(interactive, getServices());
    }
}
