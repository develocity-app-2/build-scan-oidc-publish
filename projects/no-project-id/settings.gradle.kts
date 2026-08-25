plugins {
    id("com.gradle.develocity") version "4.5.0"
}

rootProject.name = "no-project-id"

develocity {
    server = "https://dv-self-paced-training.grdev.net"

    // The control case: deliberately no projectId. Identical to the other two
    // builds in every other respect, so a difference in outcome is attributable
    // to the project ID and nothing else.

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false
    }
}
