package org.gradle.rewrite.build

import groovy.json.JsonSlurper
import org.gradle.util.VersionNumber

class GradleVersionData {

    static List<String> getNightlyVersions() {
        def releaseNightly = getLatestReleaseNightly()
        releaseNightly ? [releaseNightly] + getLatestNightly() : [getLatestNightly()]
    }

    private static String getLatestNightly() {
        new JsonSlurper().parse(new URL("https://services.gradle.org/versions/nightly")).version
    }

    private static String getLatestReleaseNightly() {
        new JsonSlurper().parse(new URL("https://services.gradle.org/versions/release-nightly")).version
    }

    static List<String> getReleasedVersions() {
        new JsonSlurper().parse(new URL("https://services.gradle.org/versions/all"))
                .findAll { !it.nightly && !it.snapshot } // filter out snapshots and nightlies
                .findAll { !it.rcFor || it.activeRc } // filter out inactive rcs
                .findAll { !it.milestoneFor } // filter out milestones
                .<String, VersionNumber, String> collectEntries { [(it.version): VersionNumber.parse(it.version as String)] }
                // TODO: Downgrade to 4.3 (or even 4.0 is Provider.map isn't needed) as per https://github.com/openrewrite/rewrite-gradle-plugin/issues/227#issuecomment-1707455588 unless future comments change this
                .findAll { it.value >= VersionNumber.parse("6.8.3") } // only 6.8.3 and above
                .inject([] as List<Map.Entry<String, VersionNumber>>) { releasesToTest, version -> // only test against latest patch versions
                    if (!releasesToTest.any { it.value.major == version.value.major && it.value.minor == version.value.minor }) {
                        releasesToTest + version
                    } else {
                        releasesToTest
                    }
                }
                .collect { it.key.toString() }
    }

}
