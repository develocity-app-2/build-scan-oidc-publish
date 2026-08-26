plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "no-project-id"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // Deliberately no projectId. With project-level access control enforcing
    // and unassociated data disallowed, the server is expected to refuse this
    // build for naming no project. Identical to the other two builds otherwise,
    // so any difference in outcome is attributable to the project ID alone.

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
