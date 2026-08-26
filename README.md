# build-scan-oidc-publish

An experiment in publishing Gradle Build Scans to Develocity from GitHub Actions, authenticating
with a **GitHub OIDC token** instead of a long-lived access key, and checking that project-level
access control constrains what that credential can publish.

Develocity server: <https://dv-self-paced-training.grdev.net> (unreleased build, with
project-level access control and workload identity support).

## Status

| | |
| --- | --- |
| Project-level access control | **verified**, in all three directions |
| OIDC token exchange | **working**: `POST /api/auth/token` returns a Develocity token |
| OIDC end to end | **working**: all six cells pass, OIDC matching the access key exactly |
| Dynamic project group allocation | **working**: verified on both the `repository` and `repository_id` claims |
| One all-repositories entry | **working**: two repositories confined to their own projects by `repository_id` alone |

`build.yml` runs both credentials side by side, so the comparison is one run
([32916347289](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32916347289)):

| | `granted` | `forbidden` | `no-project-id` |
| --- | --- | --- | --- |
| **access-key** | publishes | denied | refused, project required |
| **oidc** | publishes | denied | refused, project required |

**A token obtained through GitHub OIDC is constrained exactly as the equivalent access key is.**
That is the result the repository exists to establish.

## Resolved: the OIDC token was not scoped to its entry's projects

*(Kept because it is the failure mode most worth knowing about.)*

Once the exchange worked, a token minted through workload identity published to
`build-scan-oidc-forbidden` — a project its entry's group does not contain — where an access key
holding the same single project group was denied for the identical build.

The cause was **extra roles left on the workload identity entry** while diagnosing the 401 below,
at least one of which carried access beyond the entry's project group. Removing them made the
cell pass. So the project group does constrain minted tokens; a broad role alongside it does not.

Worth keeping for two reasons. It is the exact shape of a mis-scoped CI credential in the wild —
the grant looks correct, and the extra capability arrives from a role sitting beside it. And one
observation made it diagnosable: the same token was still refused for `no-project-id`, so it was
not simply carrying "access all data without an associated project". It was scoped enough to be
refused unassociated data, yet not scoped to the granted project, which pointed at a role rather
than at a missing project restriction.

## Resolved: the exchange returning 401

*(Kept because the eliminations were the expensive part. The cause was Develocity-side
configuration.)*


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

The negative cases are the point: a credential that can publish anywhere is not meaningfully
scoped.

`.github/workflows/build.yml` runs each of those under each credential — a six-cell matrix of
`credential × project`. Both arms share one job definition, so they run on the same commit and
the same runner generation. That matters more than it sounds: runs minutes apart have already
landed on different runner images carrying different Gradle versions, and a comparison split
across two workflows would carry that variable. Each project's expected outcome travels with it
through `matrix.include`.

### Why the assertions match on the message

A refused publish **does not fail the Gradle build** — the plugin prints a warning and the build
still reports `BUILD SUCCESSFUL`, so the exit code carries no signal. Each job therefore matches
the server's specific response:

| Job | Passes on |
| --- | --- |
| `granted` | a `grdev.net/s/…` scan URL appearing |
| `forbidden` | `denied the request to publish the build scan` |
| `no-project-id` | `rejected the request due to a project ID being required` |

Each cell also appends its verdict to the run summary, so all six read side by side without
opening individual jobs. The Gradle exit code is logged but deliberately not raised as an
annotation: a refused publish still exits 0, so surfacing it next to a result invites reading it
as one.

Matching the *message* rather than merely the absence of a scan URL is deliberate. Absence of a
URL is satisfied by any failure at all, so the looser check would let an unrelated breakage pass
itself off as proof of isolation — which is precisely what happened during the episode described
under **History** below. `forbidden` reports *inconclusive*, not success, when nothing publishes
for an unaccounted reason.

## Result

Run [32916347289](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32916347289),
with the server's own response behind every cell:

| Credential | Build | Server response |
| --- | --- | --- |
| access-key | `granted` | published — `/s/lbgm3t754lcmm` |
| access-key | `forbidden` | `denied the request to publish the build scan` |
| access-key | `no-project-id` | `rejected … due to a project ID being required` |
| oidc | `granted` | published — `/s/376whfovt4vwe` |
| oidc | `forbidden` | `denied the request to publish the build scan` |
| oidc | `no-project-id` | `rejected … due to a project ID being required` |

Project-level access control holds in all three directions, and holds identically whether the
build authenticates with a stored access key or with a token exchanged from a GitHub OIDC token.
No long-lived Develocity credential is needed in CI to get that property.

### Dynamic project group allocation from a token claim

The entry does not have to name its project groups. Develocity can read a **claim** from the token
and grant whichever project groups carry a matching mapping value, which lets one entry serve a
whole organisation.

Two claims were verified as the grant key, in separate runs:

| Project groups claim | Project group's IdP mapping | Result |
| --- | --- | --- |
| `repository` | `develocity-app-2/build-scan-oidc-publish` | works |
| `repository_id` | `1346406320` | works |

In both cases **Assigned project groups** was cleared on the entry, so the claim match is the only
route to a grant.

Run [32917629791](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32917629791)
behaves identically to the statically-assigned configuration:

| | `granted` | `forbidden` | `no-project-id` |
| --- | --- | --- | --- |
| **oidc** | published — `/s/zo7r3swjkiyqg` | denied | refused, project required |

Both halves matter. With **Assigned project groups** empty, the only route to publishing
`build-scan-oidc-publish` is the claim matching the group's mapping value — so `granted`
publishing proves the dynamic grant happened. And `forbidden` still being denied proves it
granted *that group only*, rather than widening access, which is the failure mode a dynamic grant
could plausibly have.

The practical appeal is that nothing here is repository-specific except the project group's own
mapping value. One entry scoped to `repository_owner` Equals `develocity-app-2` would serve every
repository in the org, each landing in whichever project group names it.

### A single all-repositories entry

The end state for a self-service model: **one** workload identity entry that any repository may
authenticate against, with the project it can reach decided entirely by its `repository_id`. No
per-repository entry configuration, because entries have no API and cannot be created on demand.

| Field | Value |
| --- | --- |
| Claim requirement | `repository` **Contains** `/` |
| Assigned project groups | *(cleared)* |
| Project groups claim | `repository_id` |
| Assigned roles | `Student` |

Then one project group per repository, its identity provider mapping set to that repository's id.

Verified across two repositories, in
[32977842282](https://github.com/develocity-app-2/build-scan-oidc-publish/actions/runs/32977842282)
and the mirror in
[demo-app](https://github.com/develocity-app-2/demo-app/actions/runs/32977846223):

| Runs in | `repository_id` | `projectId` | Result |
| --- | --- | --- | --- |
| `build-scan-oidc-publish` | 1346406320 | `build-scan-oidc-publish` | publishes |
| `build-scan-oidc-publish` | 1346406320 | `github-app-demo-app` | denied |
| `demo-app` | 1335548142 | `github-app-demo-app` | publishes |
| `demo-app` | 1335548142 | `build-scan-oidc-publish` | denied |

Each repository reaches its own project and is denied the other's, from a single shared entry that
names neither of them. The cross-check matters more than either direction alone: one repository
publishing successfully proves the dynamic grant fires, and the *other* repository being denied
the same project proves the grant is scoped rather than shared.

#### `aud` cannot be used as the claim requirement

The docs suggest exactly this for an entry with no other criteria:

> If you truly need to accept tokens with no other claim criteria, you can add a requirement for
> the issuer or audience claim.

**That does not work.** With the sole requirement `aud` Equals
`https://dv-self-paced-training.grdev.net`, and tokens carrying
`aud = 'https://dv-self-paced-training.grdev.net' (str)`, the exchange returns 401 for every
repository — including the one that had published minutes earlier under a `repository` Equals
requirement. Nothing else changed.

Two readings, indistinguishable from outside: `iss` and `aud` are validated by their own dedicated
fields and excluded from the claim-requirement matcher — the docs do call requirements *"one or
more **additional** claims"*, which hints at it — or it is a defect. Either way the advice above
is wrong as written. Only `aud` was tested; `iss` may behave the same.

`repository` **Contains** `/` is the working equivalent: every `owner/repo` value contains a
slash, the requirements list stays non-empty, and it uses a claim the matcher demonstrably reads.
`repository` **Regular Expression** `.+` should serve equally.

Note the failure mode of getting this wrong is total, not partial. An empty requirements list, a
mistyped value, and an `aud` requirement all produce the same thing: a 401 for every repository at
once. That is the safe direction — it never silently over-grants — but it is indistinguishable
from a rejected token, so a scheduled run of this matrix is worth more than it looks.

### Prefer `repository_id` over `repository`

Both work, and the id form is the safer key. GitHub emits `repository_id` and
`repository_owner_id` as **strings**, verified from two repositories:

```
repository       = 'develocity-app-2/build-scan-oidc-publish' (str)
repository_id    = '1346406320'                               (str)
```

That matters because Develocity treats numeric claims as absent, so a numeric id could not have
been matched on at all. Being strings, they can.

Name-based mappings carry a dangling-reference hazard that id-based ones do not. The `repository`
claim follows a rename, so a renamed repository silently stops matching and its publishes start
being refused — while the old name becomes available for someone else to create and inherit the
grant. An id never changes and can never be reclaimed, so both halves of that go away.

The cost is legibility: `1346406320` in the admin UI identifies nothing to a human, so whatever
creates project groups should write the repository's full name into the group's display name or
description.

Note this also settles a documentation question. The GitHub section of `workload-identity.adoc`
says to "scope on the dedicated string claims `repository_owner`, `repository`, `ref`,
`workflow_ref`, and `job_workflow_ref`", which reads like a whitelist. It is not one:
`repository_id` is absent from that list and works. The governing rule is the *type* rule stated
in the same section — string claims match, numeric/boolean/array claims are treated as absent —
and the doc itself already extends past its own list by adding `ref_type` and `environment` two
paragraphs later.

**Constraints worth knowing**, none of them in the product docs:

- **One mapping value per project group.** Many-to-one is not supported — you cannot map two
  claim values to a single project group. One-to-many is fine: one value can grant several groups.
  Documented only in the support KB article *FAQ: Using Project Groups with Identity Provider
  (IdP) Mapping*.
- **GitHub emits a fixed claim set.** The `sub` format can be customised, but arbitrary claims
  cannot be added, so the claim named here must be one GitHub already sends — `repository`,
  `repository_owner`, `ref`, `environment`, `workflow_ref`, `job_workflow_ref`, `ref_type`.
- **The two claim fields have opposite type rules.** Claim *requirements* are string-only; the
  docs say numeric, boolean and array claims "are treated as absent and the requirement fails".
  The roles and project-groups *claim* explicitly supports "string and list", where a list grants
  every group matching any element. So an array claim silently fails in one field and works in the
  other.

**Documentation status.** `workload-identity.adoc` exists only on the `release-dv-2026.3.0`
branch of `gradle/dv-docs`, not on `main`, so it is not published — PR 2614 targeted `main` and
was closed because 2026.3 is a staged directory there that version-sync would wipe; PR 2640
re-landed it on the release branch. The feature gets three sentences, which cross-reference
`identity-provider.adoc` for the mapping values — a page that does not mention project groups at
all, in either 2026.2 or 2026.3.

### Why the access key is still here

The access-key arm and the `DEVELOCITY_ACCESS_KEY` secret are kept on purpose, even though
nothing needs them to publish any more. They are the control. Every wrong turn in this
repository's history was diagnosed by having a known-good credential to run the same request
against — the 401 was pinned to the OIDC token rather than the request shape that way, and the
over-scoped token was visible only because the access key was denied for the same build in the
same run. A pure-OIDC repository would be the better demo and the worse instrument.

`probe-exchange.yml` (`workflow_dispatch`) is kept for the same reason: it exercises the exchange
alone, answering "is it the credential or everything after it?" in about a minute without a
Gradle build in the way.

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

The two ends of this want **opposite** forms of the same credential, and each rejects the other's:

| Consumer | Wants | Given the other form |
| --- | --- | --- |
| `DEVELOCITY_ACCESS_KEY` (Gradle plugin 4.5.0) | `«host»=«key»` | build fails before the plugin applies: *value is malformed* |
| `Authorization: Bearer` (REST API) | bare key | HTTP 401 |

So the exchange action must re-attach the host prefix to the token it receives, and
`probe-exchange.yml` must strip it from the stored secret. Both directions cost time here: the
stripped-prefix case made the exchange endpoint look broken for *both* credentials, and the
missing-prefix case made a working exchange look like a publishing failure.

The host-qualified form exists so a build tool cannot send a key to a server it was not issued
for. The REST API has no such notion — it sees the whole string as one token.

Note the secret is required for the access-key half of the comparison; deleting it turns that
column red rather than merely skipping it.
