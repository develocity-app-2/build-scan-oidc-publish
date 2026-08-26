plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "granted"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // The credential is granted access to this project, so this build is
    // expected to publish.
    projectId = "build-scan-oidc-publish"

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
