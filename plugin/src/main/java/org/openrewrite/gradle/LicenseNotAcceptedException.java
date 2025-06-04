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
package org.openrewrite.gradle;

import org.openrewrite.config.License;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Goal is that this class moves to rewrite-core as both gradle and Maven plugin can reuse this behaviour.
 */
public class LicenseNotAcceptedException extends RuntimeException {
    protected final Collection<License> unacceptedLicenses;

    public LicenseNotAcceptedException(Collection<License> unacceptedLicenses) {
        super(buildMessage(unacceptedLicenses));
        this.unacceptedLicenses = unacceptedLicenses;
    }

    public Collection<License> getUnacceptedLicenses() {
        return Collections.unmodifiableCollection(unacceptedLicenses);
    }

    private static String buildMessage(Collection<License> unacceptedLicenses) {
        StringBuilder sb = new StringBuilder("Unaccepted licenses detected on the classpath:");
        for (License unacceptedLicense : unacceptedLicenses) {
            sb.append("\n  - ").append(unacceptedLicense.getName());
            if (unacceptedLicense.getUrl() != null) {
                sb.append("(").append(unacceptedLicense.getUrl()).append(")");
            }
        }
        sb.append("\nEither add command line arguments \"")
                .append(unacceptedLicenses.stream().map(License::getName).map(LicenseVerifier::asOption).map(option -> "-Drewrite.acceptedLicense." + option.replaceAll(" ", "_")).collect(Collectors.joining(" ")))
                .append("\" to accept licenses or look at plugin specific solutions.\n")
                .append("This only needs to be done once. Previously accepted licenses can be revoked again by removing the corresponding lines from ")
                .append(System.getProperty("user.home"))
                .append("/.rewrite/licenses.properties");

        return sb.toString();
    }
}
