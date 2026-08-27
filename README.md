# build-scan-oidc-publish

An experiment establishing that Gradle Build Scans can be published to Develocity from GitHub
Actions using a **GitHub OIDC token** instead of a stored access key, and that project-level
access control constrains that credential exactly as it constrains an access key.

Four Gradle builds under `projects/`, identical but for their `projectId`, run under both
credentials as an eight-cell matrix. All eight cells pass.

**The design of record is
[`docs/design/oidc-build-scan-publishing.md`](../docs/design/oidc-build-scan-publishing.md)** —
written portably, so it can be applied to a new deployment. §8 covers this harness and its
results; §5.3 covers the access-key format trap that bites both ends of the exchange.

---

## Running locally

Each build runs from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`; CI uses the runner's
preinstalled Gradle. Publishing is refused until the machine has an access key for the server.
