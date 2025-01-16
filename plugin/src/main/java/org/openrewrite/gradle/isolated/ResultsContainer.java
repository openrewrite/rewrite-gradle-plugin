/*
 * Copyright 2025 the original author or authors.
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

import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.Markup;
import org.openrewrite.marker.SearchResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

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
            for (Result result : recipeRun.getChangeset().getAllResults()) {
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
                    if (!result.diff(Paths.get(""), new FencedMarkerPrinter(), true).isEmpty()) {
                        refactoredInPlace.add(result);
                    }
                }
            }
        }
    }

    /**
     * Only retains output for markers of type {@code SearchResult} and {@code Markup}.
     */
    private static class FencedMarkerPrinter implements PrintOutputCapture.MarkerPrinter {
        @Override
        public String beforeSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
            return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
        }

        @Override
        public String afterSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
            return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
        }
    }

    public @Nullable RuntimeException getFirstException() {
        for (Result result : generated) {
            for (RuntimeException error : getRecipeErrors(result)) {
                return error;
            }
        }
        for (Result result : deleted) {
            for (RuntimeException error : getRecipeErrors(result)) {
                return error;
            }
        }
        for (Result result : moved) {
            for (RuntimeException error : getRecipeErrors(result)) {
                return error;
            }
        }
        for (Result result : refactoredInPlace) {
            for (RuntimeException error : getRecipeErrors(result)) {
                return error;
            }
        }
        return null;
    }

    private List<RuntimeException> getRecipeErrors(Result result) {
        List<RuntimeException> exceptions = new ArrayList<>();
        new TreeVisitor<Tree, Integer>() {
            @Override
            public Tree preVisit(Tree tree, Integer integer) {
                Markers markers = tree.getMarkers();
                markers.findFirst(Markup.Error.class).ifPresent(e -> {
                    Optional<SourceFile> sourceFile = Optional.ofNullable(getCursor().firstEnclosing(SourceFile.class));
                    String sourcePath = sourceFile.map(SourceFile::getSourcePath).map(Path::toString).orElse("<unknown>");
                    exceptions.add(new RuntimeException("Error while visiting " + sourcePath + ": " + e.getDetail()));
                });
                return tree;
            }
        }.visit(result.getAfter(), 0);
        return exceptions;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public boolean isNotEmpty() {
        return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
    }

    /**
     * List directories that are empty as a result of applying recipe changes
     */
    public List<Path> newlyEmptyDirectories() {
        Set<Path> maybeEmptyDirectories = new LinkedHashSet<>();
        for (Result result : moved) {
            assert result.getBefore() != null;
            maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
        }
        for (Result result : deleted) {
            assert result.getBefore() != null;
            maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
        }
        if (maybeEmptyDirectories.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> emptyDirectories = new ArrayList<>(maybeEmptyDirectories.size());
        for (Path maybeEmptyDirectory : maybeEmptyDirectories) {
            try (Stream<Path> contents = Files.list(maybeEmptyDirectory)) {
                if (contents.findAny().isPresent()) {
                    continue;
                }
                Files.delete(maybeEmptyDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return emptyDirectories;
    }
}
