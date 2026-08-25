plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "build-scan-oidc-publish"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    buildScan {
        // Always publish, so every CI run produces a scan to inspect.
        publishing.onlyIf { true }
        // Wait for the upload to finish before the build exits: a backgrounded
        // upload can be killed when the runner tears the job down.
        uploadInBackground = false
    }
}
