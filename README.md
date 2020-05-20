![Logo](https://github.com/openrewrite/rewrite/raw/master/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## What is this?

This project provides a Gradle plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code (along with mass pull request and commit issuance).

Currently it provides two distinct Gradle plugins:

* **org.openrewrite.rewrite-checkstyle**. Responds to the presence of a Checkstyle configuration in a project, configuring [Rewrite Checkstyle](https://github.com/openrewrite/rewrite-checkstyle), and adding fixing as a Gradle task.
* **org.openrewrite.rewrite-metrics** (optional). Publishes metrics on checkstyle violations Rewrite Checkstyle found and fixed per Gradle project to a [Prometheus RSocket Proxy](https://github.com/micrometer-metrics/prometheus-rsocket-proxy) for use in studying the impact of autoremediation on a whole organization.

```groovy
plugins {
    id 'java'
    id 'checkstyle'
    id 'org.openrewrite.rewrite-checkstyle' version 'LATEST'
    id 'org.openrewrite.rewrite-metrics' version 'LATEST'
}

repositories {
    mavenLocal()
    mavenCentral()
}

rewriteMetrics {
    metricsUri = URI.create('tcp://mypromproxy:7102')
}
```

## Future development

This plugin will continue to involve to autoconfigure other rule sets (e.g. for framework migration and security vulnerability patching) as they become available, as well as a mechanism to configure and apply custom rule sets.