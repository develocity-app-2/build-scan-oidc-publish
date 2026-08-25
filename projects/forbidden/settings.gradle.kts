plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "forbidden"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // The whole experiment turns on this value: the credential is granted
    // access to "build-scan-oidc-publish" and nothing else.
    projectId = "build-scan-oidc-forbidden"

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
