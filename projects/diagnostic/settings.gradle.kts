plugins {
    // The workflow rewrites this version string to sweep plugin versions.
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "diagnostic"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // projectId is supplied per-invocation with -Ddevelocity.projectId, so one
    // build directory can sweep many values.

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
