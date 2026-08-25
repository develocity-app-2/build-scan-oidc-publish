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
the input.

**The same error occurs via the programmatic path, with lowercase IDs.** Run
[32879288852](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32879288852)
set `projectId` in `settings.gradle.kts` to `build-scan-oidc-publish` and
`build-scan-oidc-forbidden`. Both builds succeeded; both printed the identical message and
published nothing. That eliminates two explanations:

- **Not the ID charset.** Lowercase-and-hyphen IDs fail exactly as `myProject` did.
- **Not the system-property path.** The programmatic path fails identically, so the two
  mechanisms share a cause further downstream.

The message is printed *after* `Publishing Build Scan to Develocity...`, which places it at or
after the publish attempt rather than in local validation — consistent with the server, not the
plugin, being the one objecting.

The leading explanation now is simply that **none of these projects exist yet**. `myProject`,
`build-scan-oidc-publish` and `build-scan-oidc-forbidden` have never been created in
Develocity's **Administration → Access control → Projects**, and a server refusing an unknown
project ID with a badly worded validation message fits every observation so far. Confirming this
requires the Develocity-side setup below, which cannot be driven from here.

This run also justified the tightened assertions. A locally-rejected project ID and a
server-refused publish look **identical** from the outside — no scan URL in either case — so the
original `forbidden` check would have scored this validation error as a pass and "proved" access
control was working when nothing had been tested. Both jobs now fail on the validation message
explicitly, and `forbidden` reports it as inconclusive rather than success.

**It is the server rejecting the ID, and the ID is correct.** Two further checks pin this down.

*The plugin resolves the value properly.* Printing the property during settings evaluation gives:

```
>>> projectId type: org.gradle.api.internal.provider.DefaultProperty
>>> projectId value: [build-scan-oidc-publish]
```

so the Kotlin DSL assignment takes effect and the configured value is intact on the build side.

*The complaint only appears after authentication succeeds.* Running the same build locally with
no access key fails earlier, with the auth error, and never mentions the project ID. The project
ID message therefore comes from the server after it has accepted the credential — it is not
local validation.

*It survives the projects existing.* Run
[32880058113](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32880058113),
after the two projects were created in Develocity and the user granted access to
`build-scan-oidc-publish`, produced the identical message in both jobs. So "the project does not
exist" is not sufficient to explain it either.

What remains:

1. **The server predates project ID support on the publish path**, and answers with a generic
   validation error. Needs the server version to confirm or rule out.
2. **The created Project IDs do not match these strings exactly.** The Add Project dialog takes
   both a Display Name and a Project ID, and only the latter is what builds send.
3. **A plugin/server defect** in how plugin 4.5.0 transmits `projectId` to this server version.

Note that publishing itself is fine: with no `projectId` set at all, scans publish normally
(<https://dv-self-paced-training.grdev.net/s/qqso35eyo55mm>). Only the project-associated path
fails.

## Still open

- What does Develocity do with a `projectId` that does not exist as a project? Accept, reject,
  or auto-create?
- What exactly does an access-denied rejection look like in the build log? The `forbidden`
  assertion is currently "no scan URL, and not the validation error", which is still loose; once
  the real message is known it should be tightened to match it.
- What version is this server? `/api/version` needs authentication, so this needs someone
  signed in. It matters because it bounds whether project-level access control exists here
  at all.
- Does publishing with **no** `projectId` still work? It did before these commits, and it is
  the control case that separates "project IDs are broken here" from "these particular project
  IDs are unknown".

## Running locally

Each build is run from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`. Publishing will be rejected
until the machine has an access key for the server.
