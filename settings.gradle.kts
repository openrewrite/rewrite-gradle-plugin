rootProject.name = "rewrite-gradle-plugin"

include("plugin")
include("metrics")

plugins {
    id("com.gradle.enterprise") version "3.16.1"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "1.12.1"
}

gradleEnterprise {
    val isCiServer = System.getenv("CI")?.equals("true") ?: false
    server = "https://ge.openrewrite.org/"

    buildCache {
        remote(HttpBuildCache::class) {
            url = uri("https://ge.openrewrite.org/cache/")
            // Check access key presence to avoid build cache errors on PR builds when access key is not present
            val accessKey = System.getenv("GRADLE_ENTERPRISE_ACCESS_KEY")
            isPush = isCiServer && !accessKey.isNullOrEmpty()
        }
    }

    buildScan {
        capture {
            isTaskInputFiles = true
        }

        isUploadInBackground = !isCiServer

        publishAlways()
        this as com.gradle.enterprise.gradleplugin.internal.extension.BuildScanExtensionWithHiddenFeatures
        publishIfAuthenticated()
    }
}
