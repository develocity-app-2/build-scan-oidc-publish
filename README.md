# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity from GitHub Actions, authenticating
with a **GitHub OIDC token** instead of a long-lived access key.

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

- Project-level access control: **working and verified**, in all three directions above.
- OIDC: workflow side **done and verified** as far as it can be — the token is minted with the
  right claims and the exchange request matches `gradle/actions`' own client for this endpoint.
  The exchange currently returns **HTTP 401**, which is a *token-matching* failure: no workload
  identity entry matched the claims. Grants are not involved — a matching entry with insufficient
  roles would return a token and fail later, at publish time.
- The `DEVELOCITY_ACCESS_KEY` repository secret is **no longer read** and can be deleted.

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

## Authentication: GitHub OIDC

There is no stored Develocity credential. Each job mints an OIDC token describing itself and
trades it for a short-lived Develocity access token:

1. `permissions: id-token: write` lets the job ask GitHub for an OIDC token. It lasts about five
   minutes -- too short to survive a build, which is why it is exchanged rather than used
   directly.
2. `.github/actions/develocity-token` posts that token to `/api/auth/token` as a bearer
   credential and receives a Develocity access token valid for an hour.
3. That token is exported as `DEVELOCITY_ACCESS_KEY`, so the build authenticates exactly as it
   did before. Nothing in the three builds changed.

The exchange **deliberately requests no `permissions` or `projectIds`**. The returned token
carries precisely what the matching workload identity entry grants, which is the thing under
test; narrowing the request would test the request instead. The endpoint cannot mint a token with
more access than the credential presenting it, so the entry's grants are the ceiling.

The action logs the claims Develocity matches on (`iss`, `aud`, `repository`, `repository_owner`,
`ref`, `workflow_ref`). The tokens themselves are masked; the claims are not secret, and they are
the first thing to check when an entry does not match.

### Claims this workflow actually presents

Measured from run
[32909709401](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32909709401),
not assumed:

```
iss              = 'https://token.actions.githubusercontent.com'
aud              = 'https://dv-self-paced-training.grdev.net'
repository       = 'develocity-app-2/build-scan-oidc-publish'
repository_owner = 'develocity-app-2'
ref              = 'refs/heads/main'
workflow_ref     = 'develocity-app-2/build-scan-oidc-publish/.github/workflows/build.yml@refs/heads/main'
```

Until a matching entry exists, the exchange fails and every job fails with:

```
curl: (22) The requested URL returned error: 401
Exchange failed. Develocity returned: {"status":401,...,"title":"Something was wrong with the request."}
```

That 401 is the expected state before configuration, and it is the whole of what is outstanding:
the workflow mints the token and presents it correctly.

### Develocity configuration

In **Administration -> Access control -> Workload identity**, select **Add**:

| Field | Value |
| --- | --- |
| Name | `build-scan-oidc-publish GitHub Actions` |
| Issuer | `https://token.actions.githubusercontent.com` |
| Audience | `https://dv-self-paced-training.grdev.net` |
| Claim requirement | `repository` **Equals** `develocity-app-2/build-scan-oidc-publish` |
| Assigned roles | a role carrying permission to publish a Build Scan |
| Assigned project groups | `Group to publish with OIDC` |

Notes that matter:

- **The Audience must match** the `audience` the workflow requests, which is the server URL
  above. GitHub defaults the audience to the repository owner URL when none is passed; this
  workflow passes one explicitly, so the entry must carry the same value.
- **At least one claim requirement is required.** An entry with none never matches any token.
- **Assigned roles are not inherited from any user.** The workload identity is its own principal,
  so it needs a role granting scan publication in its own right -- `testuser`'s roles do not
  apply to it.
- **The project groups are what make the experiment work.** `Group to publish with OIDC` contains
  only `build-scan-oidc-publish`, so the token can publish there and nowhere else -- which is
  what `forbidden` and `no-project-id` assert.
- Use **Test** on the entry to confirm Develocity can reach GitHub's JWKS endpoint before saving.
  Develocity fetches `https://token.actions.githubusercontent.com/.well-known/openid-configuration`
  and the `jwks_uri` from it; if that egress is blocked, token validation fails after 24 hours of
  failed refreshes even once it is working.

Scoping on `repository` pins this to one repository. Tightening further to a branch or workflow
file is possible with `ref` or `workflow_ref`, but note the docs' warning: **Starts With is a
literal prefix check**, so `repository` Starts With `develocity-app-2/build-scan-oidc-publish`
would also match a `-fork` repository. Equals avoids the issue entirely. Do not scope on `sub`,
whose format changed for repositories created after 2026-07-15.

## Running locally

Each build is run from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`. Publishing will be rejected
until the machine has an access key for the server.
