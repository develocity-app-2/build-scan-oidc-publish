# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity from GitHub Actions, authenticating
with a **GitHub OIDC token** instead of a long-lived access key, and checking that project-level
access control constrains what that credential can publish.

Develocity server: <https://dv-self-paced-training.grdev.net> (unreleased build, with
project-level access control and workload identity support).

## Status

| | |
| --- | --- |
| Project-level access control | **verified**, in all three directions — but under *access-key* authentication |
| OIDC token exchange | **blocked**: `POST /api/auth/token` returns HTTP 401 for a GitHub OIDC token |
| Workflow side of OIDC | done; token is minted with correct claims and presented correctly |

Because the exchange fails, every job in `build.yml` currently fails at its first step. The
access-control result below was established before the switch to OIDC and has not yet been
re-confirmed under it.

## Open problem: the OIDC exchange is rejected

`POST /api/auth/token` returns **401** when presented with a GitHub Actions OIDC token, and
**200** when presented with an access key — same endpoint, same headers, same job, seconds apart:

```
== access key, /api/auth/token ==
  access key -> HTTP 200
     body length 1196
== access key, /api/builds ==
  /api/builds -> HTTP 200
== OIDC token, /api/auth/token ==
  oidc -> HTTP 401
     body: {"status":401,"type":"urn:gradle:develocity:api:problems:client-error",
            "title":"Something was wrong with the request."}
```

Reproduce with `.github/workflows/probe-exchange.yml` (`workflow_dispatch`), which runs exactly
that comparison. Latest run:
[32913917417](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32913917417).

### The request

```
POST https://dv-self-paced-training.grdev.net/api/auth/token?expiresInHours=1
Content-Type: application/json
Authorization: Bearer <github oidc jwt>
(empty body)
```

Headers and empty body match `gradle/actions`' own `ShortLivedTokenClient`
(`sources/src/develocity/short-lived-token.ts`), which is the only reference implementation of
this endpoint — `gradle/actions` does not implement the OIDC path at all, so the shape is
extrapolated from its access-key path. That same shape returns 200 with an access key.

### The token's claims

Decoded in-job from the token actually sent:

```
iss              = https://token.actions.githubusercontent.com
aud              = https://dv-self-paced-training.grdev.net
repository       = develocity-app-2/build-scan-oidc-publish
repository_owner = develocity-app-2
ref              = refs/heads/main
workflow_ref     = develocity-app-2/build-scan-oidc-publish/.github/workflows/build.yml@refs/heads/main
```

### The workload identity entry

| Field | Value |
| --- | --- |
| ID | `build-scan-oidc-publish-id` |
| Issuer | `https://token.actions.githubusercontent.com` |
| Audience | `https://dv-self-paced-training.grdev.net` |
| Claim requirement | `repository` **Equals** `develocity-app-2/build-scan-oidc-publish` |
| Assigned project groups | `Group to publish with OIDC` |

Issuer, audience and the `repository` requirement match the claims above exactly.

### What has been eliminated

- **The request shape.** The identical request with an access key returns 200 from the same
  endpoint in the same job.
- **The entry's field values.** Verified against the admin UI; they match the claims verbatim.
- **The token.** A real JWT that decodes, with the correct `aud` and `repository`.
- **Authorization.** Additional roles were assigned to the entry with no change, which is
  consistent with 401 being authentication. A grants problem would instead yield a token that
  failed later, at publish time, with the denial message this repository already knows.
- **GitHub's issuer.** The discovery document self-reports
  `issuer = 'https://token.actions.githubusercontent.com'` — byte-identical to the entry, no
  trailing-slash discrepancy — and `jwks_uri` serves 4 RSA/RS256 keys, the material the docs say
  Develocity requires.

### What has not been tested

- **Develocity's egress to the JWKS endpoint.** The docs give this its own troubleshooting
  section because it produces exactly this symptom. **Test issuer** on the entry settles it.
- **A global enable for workload identity**, separate from the entry, if this build has one.
- **The server's own logs.** The docs point at WARN-level `WorkloadIdentityRegistry` messages,
  which is where the actual reason will be and the one place black-box testing cannot reach.

## What the repository tests

Three independent Gradle builds, byte-for-byte identical except for the `projectId` line:

| Build | `projectId` | Expected outcome |
| --- | --- | --- |
| `projects/granted` | `build-scan-oidc-publish` | scan publishes |
| `projects/forbidden` | `build-scan-oidc-forbidden` | publish denied |
| `projects/no-project-id` | *(none)* | publish refused — a project is required |

`.github/workflows/build.yml` runs each as a separate job. The negative cases are the point: a
credential that can publish anywhere is not meaningfully scoped.

### Why the assertions match on the message

A refused publish **does not fail the Gradle build** — the plugin prints a warning and the build
still reports `BUILD SUCCESSFUL`, so the exit code carries no signal. Each job therefore matches
the server's specific response:

| Job | Passes on |
| --- | --- |
| `granted` | a `grdev.net/s/…` scan URL appearing |
| `forbidden` | `denied the request to publish the build scan` |
| `no-project-id` | `rejected the request due to a project ID being required` |

Matching the *message* rather than merely the absence of a scan URL is deliberate. Absence of a
URL is satisfied by any failure at all, so the looser check would let an unrelated breakage pass
itself off as proof of isolation — which is precisely what happened during the episode described
under **History** below. `forbidden` reports *inconclusive*, not success, when nothing publishes
for an unaccounted reason.

## Result: project-level access control

Verified under access-key authentication, run
[32885817215](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32885817215):

| Build | Server response | |
| --- | --- | --- |
| `granted` | published — `/s/vlm4gqyo3f4ds` | PASS |
| `forbidden` | `denied the request to publish the build scan (used access key prefix '…')` | PASS |
| `no-project-id` | `rejected the request due to a project ID being required` | PASS |

Access control holds in all three directions: the granted project publishes, the ungranted one is
denied, and a build naming no project is refused outright. Re-confirming this under OIDC is
blocked on the exchange above; nothing in the three builds needs to change for it.

## How authentication works

There is no stored Develocity credential in the build jobs. Each mints an OIDC token describing
itself and trades it for a short-lived Develocity access token:

1. `permissions: id-token: write` lets the job ask GitHub for an OIDC token. It lasts about five
   minutes — too short to survive a build, which is why it is exchanged rather than used
   directly.
2. `.github/actions/develocity-token` posts it to `/api/auth/token` as a bearer credential and
   receives a Develocity access token valid for an hour.
3. That token is exported as `DEVELOCITY_ACCESS_KEY`, so the build authenticates exactly as it
   did under a static key. Nothing in the three builds changed.

The exchange **deliberately requests no `permissions` or `projectIds`**. The returned token then
carries precisely what the matching workload identity entry grants, which is the thing under
test; narrowing the request would test the request instead. The endpoint cannot mint a token with
more access than the credential presenting it, so the entry's grants are the ceiling.

The action logs the claims Develocity matches on. Tokens are masked; claims are not secret, and
are the first thing to check when an entry does not match.

### Configuring the Develocity side

**Administration → Access control → Workload identity → Add**, with the values in the table
above. Points that cost time:

- **Audience must match** what the workflow requests. This workflow passes the server URL
  explicitly rather than taking GitHub's default of the repository owner URL
  (`https://github.com/develocity-app-2`), so the entry must carry the server URL.
- **At least one claim requirement is mandatory.** An entry with none never matches any token.
- **Roles are not inherited from any user.** The workload identity is its own principal and needs
  a publish-capable role in its own right; `testuser`'s roles do not apply to it. This is easy to
  miss, since the access key inherited its permissions from a user and this does not.
- **The project groups are what make the experiment work.** `Group to publish with OIDC` contains
  only `build-scan-oidc-publish`, so the token can publish there and nowhere else — which is what
  `forbidden` and `no-project-id` assert.
- **Test issuer** confirms Develocity can reach GitHub's JWKS endpoint. If that egress breaks
  later, validation keeps working for 24 hours on cached keys and then fails.

Scoping on `repository` pins this to one repository. Tightening to a branch or workflow file is
possible with `ref` or `workflow_ref`, but **Starts With is a literal prefix check**, so
`repository` Starts With `develocity-app-2/build-scan-oidc-publish` would also match a `-fork`
repository. Equals avoids it. Do not scope on `sub`, whose format changed for repositories
created after 2026-07-15 — which includes this one.

## History: the project ID red herring

For most of a day every project ID was rejected with `The project ID should be a non empty string
of 256 chars maximum`, whatever the build did. The cause was **an experimental setting enabled on
this Develocity instance**, since unset. The elimination is worth keeping:

- Not the ID's value or shape — an existing short alphanumeric project ID failed the same way as
  the hyphenated ones and as a non-existent one.
- Not the mechanism — programmatic `projectId` and `-Ddevelocity.projectId` behaved identically.
- Not the plugin version — 4.0.3, 4.4.3 and 4.5.0 all failed, and 4.0.3 emitted the *identical*
  sentence despite being a different plugin generation. A string surviving three generations
  verbatim comes from the server.
- Not the project or the grants — both verified in the admin UI.
- Not the credential's permissions — the same key published fine with no project ID at all.
- Not local validation — with no access key the build failed earlier, on authentication, and
  never mentioned the project ID.

The `no-project-id` build began as a control for this, and localised the fault to the project ID
path specifically. It is now the third leg of the result rather than a control.

## Running locally

Each build runs from its own directory:

```
cd projects/granted && gradle build
```

There is no wrapper, so this uses whatever Gradle is on your `PATH`; CI uses the runner's
preinstalled Gradle. Publishing is refused until the machine has an access key for the server.

## A note on the access key format

`DEVELOCITY_ACCESS_KEY` accepts the `«host»=«key»` form, which build tools parse to avoid sending
a key to the wrong server. The REST API does not: `Authorization: Bearer` takes the bare key, and
the host-qualified form is rejected as one malformed token with a 401 indistinguishable from the
OIDC failure above. `probe-exchange.yml` strips the prefix for this reason. The build jobs are
unaffected, as they hand the value to the Gradle plugin, which understands both forms.
