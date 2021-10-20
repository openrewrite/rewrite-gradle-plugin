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
package org.openrewrite.gradle;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.gradle.RewriteReflectiveFacade.InMemoryExecutionContext;
import org.openrewrite.gradle.RewriteReflectiveFacade.Parser;
import org.openrewrite.gradle.RewriteReflectiveFacade.SourceFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;


public class ResourceParser {
    private static final Logger logger = Logging.getLogger(ResourceParser.class);
    private final RewriteReflectiveFacade rewrite;

    public ResourceParser(RewriteReflectiveFacade rewrite) {
        this.rewrite = rewrite;
    }

    public List<SourceFile> parse(Path projectDir, Collection<Path> alreadyParsed, InMemoryExecutionContext ctx) {

        List<SourceFile> sourceFiles = new ArrayList<>();
        sourceFiles.addAll(parseSourceFiles(ctx, rewrite.jsonParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, rewrite.xmlParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, rewrite.yamlParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, rewrite.propertiesParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, rewrite.hclParser(), projectDir, alreadyParsed));

        return sourceFiles;
    }

    public List<SourceFile> parseSourceFiles(InMemoryExecutionContext ctx,
                                             Parser parser,
                                             Path projectDir,
                                             Collection<Path> alreadyParsed) {
        try {
            List<Path> resourceFiles = Files.find(projectDir, 16, (path, attrs) -> {
                try {
                    if (alreadyParsed.contains(projectDir.relativize(path))) {
                        return false;
                    }

                    if (path.toString().contains("/build/") || path.toString().contains("/out/") || path.toString().contains("/.gradle/")) {
                        return false;
                    }

                    if (attrs.isDirectory() || Files.size(path) == 0) {
                        return false;
                    }
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
                return parser.accept(path);
            }).collect(Collectors.toList());
            alreadyParsed.addAll(resourceFiles);

            return parser.parse(resourceFiles, projectDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
