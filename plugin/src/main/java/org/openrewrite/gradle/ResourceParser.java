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
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class ResourceParser {
    private static final Logger logger = Logging.getLogger(ResourceParser.class);
    private final List<String> exclusions;
    private final int sizeThresholdMb;

    public ResourceParser(List<String> exclusions, int thresholdMb) {
        this.exclusions = exclusions;
        sizeThresholdMb = thresholdMb;
    }


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
                String pathStr = path.toString();
                if (pathStr.contains("/build/") || pathStr.contains("/out/") || pathStr.contains("/.gradle/")
                        || pathStr.contains("/node_modules/") || pathStr.contains("/.metadata/")) {
                    return false;
                }
                for (String exclusion : exclusions) {
                    PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                    if(matcher.matches(baseDir.relativize(path))) {
                        alreadyParsed.add(path);
                        return false;
                    }
                }

                if (alreadyParsed.contains(path)) {
                    return false;
                }
                
                if (attrs.isDirectory() || attrs.size() == 0) {
                    alreadyParsed.add(path);
                    return false;
                }

                if(!parser.accept(path)) {
                    return false;
                }

                long fileSize = attrs.size();
                if((sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L)) {
                    alreadyParsed.add(path);
                    logger.lifecycle("Skipping parsing " + path + " as its size + "  + fileSize / (1024L * 1024L) +
                            "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                    return false;
                }

                return true;
            }).collect(Collectors.toList());
            alreadyParsed.addAll(resourceFiles);

            return parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
