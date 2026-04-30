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

import org.gradle.api.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.openrewrite.maven.cache.CompositeMavenPomCache;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;

import java.nio.file.Paths;

class MavenPomCacheBuilder {
    private final Logger logger;

    MavenPomCacheBuilder(Logger logger) {
        this.logger = logger;
    }

    @Nullable MavenPomCache build(@Nullable String pomCacheDirectory) {
        if (isJvm64Bit()) {
            try {
                if (pomCacheDirectory == null) {
                    //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                    return new CompositeMavenPomCache(
                            new InMemoryMavenPomCache(),
                            new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home")))
                    );
                }
                return new CompositeMavenPomCache(
                        new InMemoryMavenPomCache(),
                        new RocksdbMavenPomCache(Paths.get(pomCacheDirectory))
                );
            } catch (Throwable e) {
                logger.warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                logger.debug("Unable to initialize RocksdbMavenPomCache", e);
            }
        } else {
            logger.warn("RocksdbMavenPomCache is not supported on 32-bit JVM. Falling back to InMemoryMavenPomCache");
        }

        return null;
    }

    private static boolean isJvm64Bit() {
        //It appears most JVM vendors set this property. Only return false if the
        //property has been set AND it is set to 32.
        return !"32".equals(System.getProperty("sun.arch.data.model", "64"));
    }
}
