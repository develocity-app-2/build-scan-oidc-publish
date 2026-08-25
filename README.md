# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity from GitHub Actions, working
towards authenticating with a **GitHub OIDC token** instead of a long-lived access key.

Develocity server: <https://dv-self-paced-training.grdev.net>

## What this repository is currently testing

That project-level access control actually constrains what a CI credential can publish:

- the workflow **can** publish a Build Scan to the project it has been granted, and
- the workflow **cannot** publish a Build Scan to a project it has not been granted.

The second half is the one that matters. A credential that can publish anywhere is not
meaningfully scoped, so the negative case is the real test. Both hold — see **Result** below.

### Layout

Two independent Gradle builds, byte-for-byte identical except for one line:

| Build | `projectId` | Expected outcome |
| --- | --- | --- |
| `projects/granted` | `build-scan-oidc-publish` | scan publishes |
| `projects/forbidden` | `build-scan-oidc-forbidden` | publish rejected |
| `projects/no-project-id` | *(none)* | scan publishes |

`.github/workflows/build.yml` runs each as a separate job and asserts on the outcome.

The third build is a control, and it discriminates between the readings of a failure:

- If it publishes while the other two do not, the fault is specific to the **project ID path**.
- If it also fails, either publishing is broken outright or **Allow data without an associated
  project** is now unchecked, since enforcement is on.
- If it reports the *project ID* error despite setting none, the plugin is sending an empty
  project ID unconditionally — which would explain every observation above.

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

One thing to keep in mind if this is ever rebuilt: **if the credential's user has broad access**,
the `forbidden` publish will succeed and the experiment measures nothing. The user must be scoped
to a project group containing only the granted project.

## Status

- Publishing with a static access key: **working**.
- Project-level access control: **working and verified**, in all three directions above.
- OIDC: not started.

## Result

Project-level access control does constrain what this credential can publish. Run
[32885373656](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32885373656)
and later:

| Build | `projectId` | Outcome |
| --- | --- | --- |
| `projects/granted` | `build-scan-oidc-publish` | publishes |
| `projects/forbidden` | `build-scan-oidc-forbidden` | `denied the request to publish the build scan` |
| `projects/no-project-id` | *(none)* | `rejected the request due to a project ID being required` |

All three are asserted on the specific message, not merely on the absence of a scan URL. That
distinction matters: requiring the denial text is what stops an unrelated failure from passing
itself off as proof of isolation.

The third build began life as a control for the investigation below, and has become the third
leg of the result: with enforcement on and unassociated data disallowed, a build that names no
project is refused outright.

## How this was made to work

For most of a day every project ID was rejected with `The project ID should be a non empty string
of 256 chars maximum`, whatever the build did. The cause was **an experimental setting enabled on
this Develocity instance**, since unset. Worth keeping, because the elimination was the useful
part:

- Not the ID's value or shape — an existing short alphanumeric project ID failed the same way as
  the hyphenated ones and as a non-existent one.
- Not the mechanism — programmatic `projectId` and `-Ddevelocity.projectId` behaved identically.
- Not the plugin version — 4.0.3, 4.4.3 and 4.5.0 all failed, and 4.0.3 emitted the *identical*
  sentence despite being a different plugin generation. A string that survives three generations
  verbatim comes from the server.
- Not the project or the grants — both were verified in the admin UI.
- Not the credential's permissions — the same key published fine with no project ID at all.
- Not local validation — with no access key the build failed earlier, on authentication, and
  never mentioned the project ID.

Two things carried their weight here. The **control build** localised the fault to the project ID
path specifically. And the `forbidden` job was written to report *inconclusive* rather than pass
when nothing published for the wrong reason — without that, this whole period would have shown a
green `forbidden` and looked like proof that isolation worked, while proving nothing.

## Next: OIDC

Replace the static access key with a GitHub OIDC token exchange. That needs `id-token: write` in
the workflow's `permissions:` block, and will exchange the OIDC token for a short-lived
Develocity token via `POST /api/auth/token`, which takes `permissions` and `projectIds` and
cannot mint a token with more access than the credential presenting it. The three jobs here
become the regression test for it: the granted/forbidden/no-project split should hold exactly as
it does now once the credential changes.

## Running locally

Each build is run from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`. Publishing will be rejected
until the machine has an access key for the server.
