# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity from GitHub Actions, working
towards authenticating with a **GitHub OIDC token** instead of a long-lived access key.

Develocity server: <https://dv-self-paced-training.grdev.net>

## What this repository is currently testing

That project-level access control actually constrains what a CI credential can publish:

- the workflow **can** publish a Build Scan to the project it has been granted, and
- the workflow **cannot** publish a Build Scan to a project it has not been granted.

The second half is the one that matters. A credential that can publish anywhere is not
meaningfully scoped, so the negative case is the real test.

### Layout

Two independent Gradle builds, byte-for-byte identical except for one line:

| Build | `projectId` | Expected outcome |
| --- | --- | --- |
| `projects/granted` | `build-scan-oidc-publish` | scan publishes |
| `projects/forbidden` | `build-scan-oidc-forbidden` | publish rejected |

`.github/workflows/build.yml` runs each as a separate job and asserts on the outcome.

### Why the assertions grep the log

A rejected publish **does not fail the Gradle build** — the plugin prints a warning and the
build still reports `BUILD SUCCESSFUL`. So the exit code proves nothing, and each job asserts
on whether a scan URL was emitted:

- `granted` fails if no `grdev.net/s/…` URL appears.
- `forbidden` fails if one *does*.

## Develocity-side setup

This half cannot be automated from here — it needs a signed-in user with the
**Configure projects** permission. In **Administration → Access control → Projects**:

1. **Add project** with Project ID `build-scan-oidc-publish`.
2. **Add project** with Project ID `build-scan-oidc-forbidden`.
3. Create a project group containing **only** `build-scan-oidc-publish`, and assign it to the
   user whose access key is in the `DEVELOCITY_ACCESS_KEY` secret.
4. Make sure that user has **no** route to `build-scan-oidc-forbidden` — not via another project
   group, and not via a permission that reads across projects regardless.
5. Check **Enable project-level access control**, and save.

> [!WARNING]
> Develocity **cannot delete projects**. Both IDs above are permanent on this server once
> created, so change them in the two `settings.gradle.kts` files first if you want different
> names.

Two things to watch out for, because either one silently invalidates the negative case:

- **If the credential's user has broad access**, the `forbidden` publish will succeed and the
  experiment will be measuring nothing. The user must be scoped.
- **While enforcement is disabled**, Develocity does not enforce project access at all, so
  `forbidden` publishes too. The `forbidden` job is therefore expected to be **red until step 5
  is done** — that red is the experiment's "before" reading, not a broken build.

## Status

- Publishing with a static access key works: first successful scan was
  <https://dv-self-paced-training.grdev.net/s/qqso35eyo55mm>.
- `DEVELOCITY_ACCESS_KEY` is set as a repository secret.
- Project-level access control: **not yet configured** — the steps above are outstanding.
- OIDC: not started. It will need `id-token: write` in the workflow's `permissions:` block, and
  will likely exchange the OIDC token for a short-lived Develocity access token via
  `POST /api/auth/token`, which takes `permissions` and `projectIds` parameters and cannot mint
  a token with more access than the credential presenting it.

## Findings so far

**Setting `projectId` via the documented system property failed.** Run
[32878985936](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32878985936)
invoked `gradle build -Ddevelocity.projectId=myProject`, which is verbatim the form the
[plugin manual](https://docs.develocity.ai/gradle/4.5/gradle-plugin/#configuring-project-identifier)
documents. The build succeeded but publishing did not happen:

```
Publishing Build Scan to Develocity...
The project ID should be a non empty string of 256 chars maximum
```

`myProject` is a non-empty string well under 256 characters, so the message does not describe
the input. Untested hypotheses, in rough order of likelihood:

1. The ID charset is narrower than the message admits and `myProject` is rejected for its
   uppercase letter. The two builds here use lowercase-and-hyphens IDs, which would sidestep it.
2. This server predates project ID support, and the validation is server-side.
3. The system-property path is broken in plugin 4.5.0 in a way the programmatic path is not.

The two builds set `projectId` **programmatically** instead, which distinguishes 1 and 3 from 2:
if `projects/granted` publishes, the programmatic path works and the server supports project IDs.

This also exposed a trap in the negative test. A locally-rejected project ID and a
server-refused publish look **identical** from the outside — no scan URL in either case — so the
`forbidden` job would have scored that validation error as a pass. Both jobs now fail on the
validation message explicitly, and the `forbidden` job reports it as inconclusive rather than
success: if the plugin never asked the server, nothing was proved about access control.

## Still open

- What does Develocity do with a `projectId` that does not exist as a project? Accept, reject,
  or auto-create?
- What exactly does an access-denied rejection look like in the build log? The `forbidden`
  assertion is currently "no scan URL, and not the validation error", which is still loose; once
  the real message is known it should be tightened to match it.
- What version is this server? It matters for hypothesis 2, and `/api/version` needs
  authentication.

## Running locally

Each build is run from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`. Publishing will be rejected
until the machine has an access key for the server.
