# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity using a **GitHub OIDC token**
instead of a long-lived access key.

Develocity server: <https://dv-self-paced-training.grdev.net>

## Where the experiment is now

Step 1 — a plain Gradle build that publishes a scan on every CI run, authenticated with a
**static access key**. This is the baseline; the point is to confirm publishing works before
changing how the build authenticates.

- `settings.gradle.kts` applies the Develocity Gradle plugin and points it at the server.
  `uploadInBackground = false` so the upload finishes before the runner tears the job down.
- `build.gradle.kts` is a trivial `java-library` with one class and one JUnit 5 test — enough
  for the scan to carry real compile and test data.
- `.github/workflows/build.yml` runs `./gradlew build` with `DEVELOCITY_ACCESS_KEY` in the
  environment of the Gradle step. It deliberately does **not** use
  `gradle/actions/setup-gradle`: that action injects its own Develocity init script and custom
  values, and this experiment needs the build's own configuration to be the only thing in play.

## Setup required before the first run

The workflow reads the key from a repository secret:

```
gh secret set DEVELOCITY_ACCESS_KEY --repo develocity-app-2/build-scan-oidc-publish
```

Generate the key value from the Develocity UI (*My settings → Access keys*), or locally with
`./gradlew provisionDevelocityAccessKey` and read it out of `~/.gradle/develocity/keys.properties`.

The secret value must be the bare key, **not** the `host=key` form used in
`keys.properties` — the `DEVELOCITY_ACCESS_KEY` variable accepts either, but the bare key is
unambiguous for a single server.

## Next steps

1. Project-level access control: create a Develocity project for this repository and grant the
   user access to it, so scans land against a project rather than the whole server.
2. Replace the static key with a GitHub OIDC token exchange. That will need
   `id-token: write` added to the workflow's `permissions:` block.

## Running locally

```
./gradlew build
```

Publishing will be rejected until this machine has an access key for the server.
