plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "forbidden"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // The credential has no access to this project, so the server is expected
    // to deny the publish. This is the negative case the experiment exists for.
    projectId = "build-scan-oidc-forbidden"

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
