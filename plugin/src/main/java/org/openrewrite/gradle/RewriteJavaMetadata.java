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

public class RewriteJavaMetadata {
    private final String sourceCompatibility;
    private final String targetCompatibility;
    private final String groupId;
    private final String artifactId;
    private final String version;

    public RewriteJavaMetadata(String sourceCompatibility, String targetCompatibility, String groupId, String artifactId, String version) {
        this.sourceCompatibility = sourceCompatibility;
        this.targetCompatibility = targetCompatibility;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getSourceCompatibility() {
        return sourceCompatibility;
    }

    public String getTargetCompatibility() {
        return targetCompatibility;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }
}
