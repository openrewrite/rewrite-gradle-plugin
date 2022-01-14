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

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.internal.lang.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradlePropertiesHelper {
    private static final Logger LOGGER = Logging.getLogger(GradlePropertiesHelper.class);

    public static void checkAndLogMissingJvmModuleExports(@Nullable String jvmProperties) {
        try {
            String version = System.getProperty("java.version");
            int dot = version.indexOf(".");
            String majorVersionString = dot > 0 ? version.substring(0, dot) : version;
            int majorVersion = Integer.parseInt(majorVersionString);
            List<String> requiredExportPackages = new ArrayList<>();
            if (majorVersion > 15) {
                requiredExportPackages.addAll(Arrays.asList("code", "comp", "file", "main", "tree", "util"));
            }
            if (majorVersion > 16) {
                requiredExportPackages.addAll(Arrays.asList("jvm", "model", "processing"));
            }
            if (!requiredExportPackages.isEmpty()) {
                Pattern pattern = Pattern.compile("--add-exports\\sjdk\\.compiler/com\\.sun\\.tools\\.javac\\.(\\w+)=ALL-UNNAMED");
                Matcher matcher = pattern.matcher(jvmProperties != null ? jvmProperties : "");
                Set<String> exportedPackages = new HashSet<>(requiredExportPackages);
                while (matcher.find()) {
                    String pkg = matcher.group(1);
                    exportedPackages.remove(pkg);
                }
                if (!exportedPackages.isEmpty()) {
                    StringBuilder errMessage = new StringBuilder( "Java ").append(version).append(" protected module access not exported for:");
                    for (String missingModuleExport : exportedPackages) {
                        errMessage.append("\n\tcom.sun.tools.javac.").append(missingModuleExport);
                    }
                    LOGGER.error(errMessage.toString());
                    LOGGER.error("The following exports should be added to your gradle.properties file.");
                    StringBuilder infoMessage = new StringBuilder("org.gradle.jvmargs=");
                    for (String exp : exportedPackages) {
                        infoMessage.append("--add-exports jdk.compiler/com.sun.tools.javac.").append(exp).append("=ALL-UNNAMED ");
                    }
                    LOGGER.error(infoMessage.toString());
                    LOGGER.error("");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error checking JVM protected module exports", e);
        }
    }
}
