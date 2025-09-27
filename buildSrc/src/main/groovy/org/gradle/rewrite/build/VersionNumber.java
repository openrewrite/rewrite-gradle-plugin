/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.rewrite.build;

import javax.annotation.Nullable;

// XXX Inlined following removal https://github.com/gradle/gradle/issues/34546
public class VersionNumber {

    private static final DefaultScheme DEFAULT_SCHEME = new DefaultScheme();
    public static final VersionNumber UNKNOWN = version(0);

    private final int major;
    private final int minor;
    private final int micro;
    private final int patch;
    private final String qualifier;
    private final DefaultScheme scheme;

    private VersionNumber(int major, int minor, int micro, int patch, @Nullable String qualifier, DefaultScheme scheme) {
        this.major = major;
        this.minor = minor;
        this.micro = micro;
        this.patch = patch;
        this.qualifier = qualifier;
        this.scheme = scheme;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        VersionNumber that = (VersionNumber) o;
        return major == that.major && minor == that.minor && micro == that.micro && patch == that.patch && java.util.Objects.equals(qualifier, that.qualifier);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(major, minor, micro, patch, qualifier);
    }

    @Override
    public String toString() {
        return scheme.format(this);
    }

    public static VersionNumber version(int major) {
        return version(major, 0);
    }

    public static VersionNumber version(int major, int minor) {
        return new VersionNumber(major, minor, 0, 0, null, DEFAULT_SCHEME);
    }

    public static VersionNumber parse(String versionString) {
        return DEFAULT_SCHEME.parse(versionString);
    }

    /**
     * Returns the version number scheme.
     */
    @Deprecated
    public interface Scheme {
        VersionNumber parse(String value);

        String format(VersionNumber versionNumber);
    }

    private static class DefaultScheme implements Scheme {
        private static final String VERSION_TEMPLATE = "%d.%d.%d%s";
        final int depth;

        public DefaultScheme() {
            this.depth = 3;
        }

        @Override
        public String format(VersionNumber versionNumber) {
            return String.format(VERSION_TEMPLATE, versionNumber.major, versionNumber.minor, versionNumber.micro, versionNumber.qualifier == null ? "" : "-" + versionNumber.qualifier);
        }

        @Override
        public VersionNumber parse(@Nullable String versionString) {
            if (versionString == null || versionString.isEmpty()) {
                return UNKNOWN;
            }
            Scanner scanner = new Scanner(versionString);
            if (!scanner.hasDigit()) {
                return UNKNOWN;
            }
            int minor = 0;
            int micro = 0;
            int patch = 0;
            int major = scanner.scanDigit();
            if (scanner.isSeparatorAndDigit('.')) {
                scanner.skipSeparator();
                minor = scanner.scanDigit();
                if (scanner.isSeparatorAndDigit('.')) {
                    scanner.skipSeparator();
                    micro = scanner.scanDigit();
                    if (depth > 3 && scanner.isSeparatorAndDigit('.', '_')) {
                        scanner.skipSeparator();
                        patch = scanner.scanDigit();
                    }
                }
            }

            if (scanner.isEnd()) {
                return new VersionNumber(major, minor, micro, patch, null, this);
            }

            if (scanner.isQualifier()) {
                scanner.skipSeparator();
                return new VersionNumber(major, minor, micro, patch, scanner.remainder(), this);
            }

            return UNKNOWN;
        }

        private static class Scanner {
            int pos;
            final String str;

            private Scanner(String string) {
                this.str = string;
            }

            boolean hasDigit() {
                return pos < str.length() && Character.isDigit(str.charAt(pos));
            }

            boolean isSeparatorAndDigit(char... separators) {
                return pos < str.length() - 1 && oneOf(separators) && Character.isDigit(str.charAt(pos + 1));
            }

            private boolean oneOf(char... separators) {
                char current = str.charAt(pos);
                for (char separator : separators) {
                    if (current == separator) {
                        return true;
                    }
                }
                return false;
            }

            boolean isQualifier() {
                return pos < str.length() - 1 && oneOf('.', '-');
            }

            int scanDigit() {
                int start = pos;
                while (hasDigit()) {
                    pos++;
                }
                return Integer.parseInt(str.substring(start, pos));
            }

            public boolean isEnd() {
                return pos == str.length();
            }

            public void skipSeparator() {
                pos++;
            }

            @Nullable
            public String remainder() {
                return pos == str.length() ? null : str.substring(pos);
            }
        }
    }

}

