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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

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

    public List<SourceFile> parse(Path baseDir, Path projectDir, Collection<Path> alreadyParsed, ExecutionContext ctx) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        sourceFiles.addAll(parseSourceFiles(new JsonParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new XmlParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new YamlParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new PropertiesParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(HclParser.builder().build(), baseDir, projectDir, alreadyParsed, ctx));
        return sourceFiles;
    }

    public List<? extends SourceFile> parseSourceFiles(
            Parser<?> parser,
            Path baseDir,
            Path projectDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) {

        try {
            List<Path> resourceFiles = Files.find(projectDir, 16, (path, attrs) -> {
                try {
                    if (path.toString().contains("/build/") || path.toString().contains("/out/") || path.toString().contains("/.gradle/")
                            || path.toString().contains("/node_modules/") || path.toString().contains("/.metadata/")) {
                        return false;
                    }

                    if (alreadyParsed.contains(projectDir.relativize(path))) {
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

            return parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
