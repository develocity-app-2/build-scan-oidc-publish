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
- `.github/workflows/build.yml` runs `gradle build` with `DEVELOCITY_ACCESS_KEY` in the
  environment of the Gradle step. Three deliberate omissions, all in service of a clean, fast
  experiment:
  - **No `gradle/actions/setup-gradle`** — it injects its own Develocity init script and custom
    values, and the build's own configuration should be the only Develocity config in play.
  - **No Gradle wrapper** — the `ubuntu-latest` image ships Gradle 9.7.0, so invoking `gradle`
    directly skips the distribution download.
  - **No `actions/setup-java`** — the image ships JDK 21, so the step just points `JAVA_HOME` at
    `JAVA_HOME_21_X64`.

  The job therefore downloads no toolchain at all; only the plugin and test dependencies are
  fetched.

## Setup required before the first run

The workflow reads the key from a repository secret:

```
gh secret set DEVELOCITY_ACCESS_KEY --repo develocity-app-2/build-scan-oidc-publish
```

Generate the key value from the Develocity UI (*My settings → Access keys*), or locally with
`gradle provisionDevelocityAccessKey` and read it out of `~/.gradle/develocity/keys.properties`.

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
gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH` — which will not necessarily
be the 9.7.0 that CI uses. Publishing will be rejected until this machine has an access key for
the server.
