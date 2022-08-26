/*
 * Copyright ${year} the original author or authors.
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
package org.openrewrite.gradle.isolated;

import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.internal.lang.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ResultsContainer {
    final Path projectRoot;
    final RecipeRun recipeRun;
    final List<Result> generated = new ArrayList<>();
    final List<Result> deleted = new ArrayList<>();
    final List<Result> moved = new ArrayList<>();
    final List<Result> refactoredInPlace = new ArrayList<>();

    public ResultsContainer(Path projectRoot, @Nullable RecipeRun recipeRun) {
        this.projectRoot = projectRoot;
        this.recipeRun = recipeRun;
        if (recipeRun != null) {
            for (Result result : recipeRun.getResults()) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public boolean isNotEmpty() {
        return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
    }
}
