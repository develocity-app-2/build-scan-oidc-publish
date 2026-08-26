plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "foreign"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // A project belonging to a *different* repository (demo-app). This
    // repository's credential must not be able to publish here, whichever
    // direction the cross-check runs in.
    projectId = "github-app-demo-app"

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
