# Publishing Build Scans with GitHub OIDC and Project-Level Access Control

A design for letting any number of GitHub repositories publish Build Scans to Develocity with **no
stored Develocity credentials**, where each repository can publish only to its own project, and
onboarding a repository requires no change to the authentication configuration.

Everything here was established by running it against a Develocity server with project-level
access control and workload identity, and is written with placeholder names so it can be applied
to a new deployment.

**The token exchange is a CI-action concern, not a per-project one.** `gradle/actions/setup-gradle`
already exchanges a Develocity access key for a short-lived token against the same endpoint, so
OIDC support there is a change of credential rather than a new mechanism (§4.1). The exchange is
described in full below because its failure modes are visible to whoever is debugging them, and
because anything that is not a Gradle build on GitHub Actions has to perform it itself — not
because a workflow author should be writing it.

Placeholders used throughout:

| Placeholder | Meaning |
| --- | --- |
| `https://develocity.example.com` | your Develocity server |
| `«repository_id»` | GitHub's numeric-but-string repository id, e.g. `1234567890` |

Note there is no separate placeholder for the project or project group id: **both are the
repository id** (§3).

---

## 1. How it fits together

```
GitHub Actions job
  │
  │  1. mints an OIDC token describing itself (id-token: write)
  │     claims include: iss, aud, repository, repository_id, ref, workflow_ref
  ▼
POST https://develocity.example.com/api/auth/token
  Authorization: Bearer «the OIDC token»
  │
  │  2. Develocity matches the token against its workload identity entries.
  │     The matching entry decides which roles and project groups the
  │     returned token carries.
  ▼
Develocity access token  (short-lived)
  │
  │  3. passed to the build as DEVELOCITY_ACCESS_KEY
  ▼
Gradle build publishes a Build Scan to «repository_id»
  │
  └─ allowed only if the token holds a project group containing «repository_id»
```

Steps 1 and 2 are the CI action's job, not the workflow author's — see §4. The three decisions
below are worth separating regardless of who makes the request, because each fails differently:

1. **Can this workload authenticate at all?** Decided by the entry's issuer, audience and claim
   requirements. Failure is `HTTP 401` from `/api/auth/token`.
2. **What does the resulting token hold?** Decided by the entry's assigned roles and project
   groups — statically, or dynamically from a claim.
3. **May it publish to this project?** Decided by whether the token's project groups contain the
   project the build names. Failure is a denial message during publishing, *not* a build failure.

## 2. The single workload identity entry

There is **no API for workload identity entries** — they are created and edited in the UI only.
They therefore cannot be provisioned per repository, and a self-service model needs exactly one
entry that every repository shares.

Configure it once, under **Administration → Access control → Workload identity**:

| Field | Value | Why |
| --- | --- | --- |
| Issuer | `https://token.actions.githubusercontent.com` | GitHub Actions' OIDC issuer. Must match the tokens' `iss` exactly, including the absence of a trailing slash. |
| Audience | `https://develocity.example.com` | Must equal the audience the workload requests. Set it to the server URL, which is what the CI action defaults to, rather than GitHub's default of the repository owner URL. |
| Claim requirement | `iss` **Equals** `https://token.actions.githubusercontent.com` | At least one requirement is mandatory, and this one matches every GitHub Actions token. Duplicating the Issuer field looks redundant but is what makes the entry match anything at all. See §5.1 — an `aud` requirement does **not** work. |
| Assigned roles | one minimal, publish-only role | Granted unconditionally to **every repository on GitHub**. See §5.2. |
| Assigned project groups | *(empty)* | Anything here is granted to every repository. Leave it empty so authorization comes only from the claim. |
| Project groups claim | `repository_id` | Develocity reads this claim and grants every project group whose identity provider mapping equals its value. |
| Roles claim | *(empty)* | Roles are fixed; only project access varies per repository. |

Use **Test issuer** before saving to confirm Develocity can reach
`https://token.actions.githubusercontent.com/.well-known/openid-configuration` and the JWKS
endpoint named in it. If that egress later breaks, validation keeps working for 24 hours on cached
keys and then fails for everyone.

### Why `repository_id` rather than `repository`

Both work. `repository_id` is the safer key:

- **Renames and transfers.** The `repository` claim follows a rename, so a renamed repository
  silently stops matching its project group and its publishes start being refused. The id does not
  change.
- **Name reclaim.** A repository name that is freed up can be created by somebody else, who would
  inherit the grant belonging to the old one. An id can never be reclaimed.

GitHub sends `repository_id` and `repository_owner_id` as **JSON strings**, not numbers, which is
what makes this possible — Develocity treats numeric claims as absent.

The cost is legibility: an id identifies nothing to a human reading the admin UI. Whatever creates
project groups should write the repository's full name into the group's display name or
description.

## 3. What each repository needs

Per repository, two Develocity objects and one relationship — all three derived from the
repository id, so nothing has to be invented or looked up:

| Object | Id | Display name |
| --- | --- | --- |
| Project | `«repository_id»` | the repository's full name, e.g. `example-org/example-repo` |
| Project group | `«repository_id»` | as above |

and the project group's **identity provider mapping** set to `«repository_id»` as well, containing
that one project and nothing else.

**Use the repository id, not the name, for the identifiers.** The reasoning that makes
`repository_id` the right claim to match on (§2) applies just as much to the ids of the objects it
matches against: a repository rename or transfer changes its name but not its id, so nothing drifts
and nothing has to be renamed in Develocity to keep publishing working. Legibility is recovered by
the display name, which is the human-readable repository name and can be updated freely — it plays
no part in matching.

Because every identifier is the same GitHub-issued value, there is nothing to keep in sync: the
claim in the token, the group's mapping value, and the project the build names are all one number.
A CI action can derive it from `GITHUB_REPOSITORY_ID` without being told (§4.2).

A group's mapping holds exactly **one** value. Many-to-one is not supported: two claim values
cannot both map to one group. One-to-many is fine — one value may grant several groups.

Two consequences of keying on the id:

- **Projects cannot be deleted**, so every registration is permanent. A repository deleted and
  later recreated gets a *new* id and therefore a new project, leaving the old one orphaned. A
  renamed or transferred repository keeps the same id and the same project.
- Whether the same value may serve as both a project id and a project group id depends on whether
  the server namespaces them separately. If it rejects the reuse, suffix the group
  (`«repository_id»-group`); nothing else in the design changes.

Nothing else changes. The workload identity entry is untouched by onboarding, which is the point.

The repository's *build* need not change either, if Develocity injection supplies the plugin and
its configuration — see §4.2, including the one gap that currently blocks it.

## 4. The workflow side

**The exchange belongs in the CI action, not in the workflow.** `gradle/actions/setup-gradle`
already owns the equivalent step for access keys, so the intended end state is that a workflow
never mentions tokens at all:

```yaml
permissions:
  contents: read
  id-token: write        # without this there is no OIDC token to mint

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      - uses: gradle/actions/setup-gradle@v5
        with:
          develocity-url: https://develocity.example.com
          # no develocity-access-key: the action mints and exchanges an OIDC
          # token instead, using develocity-url as the audience

      - run: ./gradlew build
```

And in `settings.gradle.kts`:

```kotlin
plugins {
    id("com.gradle.develocity") version "«latest»"
}

develocity {
    server = "https://develocity.example.com"
    projectId = "«repository_id»"       // required; see §5.4

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false      // let the upload finish before the runner exits
    }
}
```

### 4.1 Why this is a small change to the action

`setup-gradle` already performs a credential-for-short-lived-token exchange against **the same
endpoint**. It exposes `develocity-access-key` and `develocity-token-expiry`, and its
`ShortLivedTokenClient` posts to `/api/auth/token?expiresInHours=…` with
`Authorization: Bearer «credential»`, `Content-Type: application/json` and an empty body, with
retries.

So the OIDC flow is not a new mechanism, it is a different bearer credential presented to
machinery that already exists:

| Step | Today (access key) | With OIDC |
| --- | --- | --- |
| Obtain a credential | read a secret | `core.getIDToken(«audience»)` |
| Exchange it | `POST /api/auth/token`, bearer = access key | identical, bearer = the OIDC token |
| Hand it to the build | set `DEVELOCITY_ACCESS_KEY` | unchanged |

What the action needs to add is the first row. The audience needs no input of its own: it should
default to `develocity-url`, which the action already takes and which the entry's Audience is set
to. Everything else — the request shape, the retries, masking, and the `«host»=«key»` formatting
the Gradle plugin demands (§5.3) — it already does.

The rest of this document deliberately describes the exchange in full anyway. Not because a
workflow author should implement it, but because the failure modes in §5 surface as opaque `401`s
and denial messages regardless of who makes the request, and because the same exchange is needed
by anything that is not a Gradle build on GitHub Actions.

### 4.2 Newly connected repositories: Develocity injection

The `settings.gradle.kts` shown above assumes the repository is already set up to publish. A
repository being connected for the first time is not, and requiring a build-file change as part of
onboarding defeats the point of self-service.

`setup-gradle` already solves this with **Develocity injection**: it applies and configures the
Develocity plugin from action inputs, for builds that do not reference it at all. The relevant
inputs today are `develocity-injection-enabled`, `develocity-url`, `develocity-plugin-version`,
`develocity-ccud-plugin-version`, `develocity-enforce-url`, `develocity-capture-file-fingerprints`
and `develocity-allow-untrusted-server`.

So a newly connected repository needs **no changes to its build at all**:

```yaml
- uses: gradle/actions/setup-gradle@v5
  with:
    develocity-injection-enabled: true
    develocity-url: https://develocity.example.com
    develocity-plugin-version: «latest»
```

with the OIDC exchange (§4.1) supplying the credential and the project id derived from the
repository (below).

**One gap.** Injection has no way to set the project id. There is no `develocity-project-id` input,
and nothing in the action or its injection scripts handles one. Since a project id is *mandatory*
once project-level access control is enforced — a build that names no project is refused outright
(§5.4) — injection as it stands cannot connect a repository to a project. Closing that is the one
enhancement this design depends on.

It is a small one, because the project id **is** the repository id (§3) and the runner already
knows it. The action can default the injected project id to `GITHUB_REPOSITORY_ID`, so nothing
repository-specific has to be passed in, configured, or kept in sync; an explicit input would only
be needed to override the convention. And the plugin already accepts the value three ways, so no
plugin change is required either: the `develocity.projectId` system property, the
`DEVELOCITY_PROJECT_ID` environment variable, or `develocity.projectId` in the injected
configuration.

That is the payoff of deriving every identifier from one GitHub-issued value. A newly connected
repository needs no build change, no project id in its workflow, and no per-repository action
input — only `develocity-injection-enabled`, the server URL, and `id-token: write`.

**The build-file route from §4 remains valid** and is the better fit for a repository that already
publishes, or one that wants its project id under version control alongside its build logic. Both
paths end in the same place; injection removes the file change from onboarding.

### 4.3 The one thing that cannot move into the action

**`id-token: write`.** This is a platform constraint, not a design choice. `permissions:` is
declared in workflow or job YAML and determines whether `ACTIONS_ID_TOKEN_REQUEST_URL` and
`ACTIONS_ID_TOKEN_REQUEST_TOKEN` are injected into the job environment at all. An action runs
inside the job and has no runtime means of elevating its own job's permissions, and the scope is
never granted by default — the org-level "read and write permissions" setting governs
`GITHUB_TOKEN` scopes, not this one. So something outside the action has to declare it.

That something need not be a person. A registrant generating the workflow file sets `permissions:`
itself, so in an automated onboarding flow this is one more line it emits rather than a human
obligation.

Everything else can be defaulted or derived, and should be:

| Value | Where it comes from |
| --- | --- |
| Audience | the action defaults it to `develocity-url`; the entry's Audience is the same server URL |
| Project id | derived from `GITHUB_REPOSITORY_ID`, which is the project id (§3) |
| Develocity plugin | applied by injection (§4.2) |

### 4.4 Doing the exchange by hand

Needed only when nothing does it for you — a non-Gradle build, another CI system, or reproducing a
failure in isolation:

```bash
# 1. GitHub issues a token describing this job. It lasts ~5 minutes, which is
#    why it is exchanged rather than used directly.
oidc=$(curl -sS --fail-with-body \
  -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=https://develocity.example.com" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["value"])')
echo "::add-mask::$oidc"

# 2. Exchange it. Requesting no permissions or projectIds means the token
#    carries exactly what the matching entry grants.
code=$(curl -sS -o /tmp/tok -w '%{http_code}' -X POST \
  "https://develocity.example.com/api/auth/token?expiresInHours=1" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $oidc" --data '')
[ "$code" = "200" ] || { echo "exchange failed: $code $(cat /tmp/tok)"; exit 1; }
tok=$(cat /tmp/tok)
echo "::add-mask::$tok"

# 3. The Gradle plugin requires the host-qualified form (§5.3).
echo "DEVELOCITY_ACCESS_KEY=develocity.example.com=${tok}" >> "$GITHUB_ENV"
```

- **Do not narrow the exchange.** `/api/auth/token` accepts `permissions` and `projectIds`
  parameters. Passing them makes the request, rather than the entry's grants, the thing that
  decides access — and cannot increase it, since the endpoint refuses to mint a token exceeding
  the credential presenting it. Omit them and let the entry decide.
- **Mask both tokens.** Neither should reach a log.

## 5. Pitfalls

Each of these cost real time, and several are indistinguishable from one another from the outside.

### 5.1 `iss` works as a match-all requirement; `aud` does not

An entry needs at least one claim requirement — **an entry with no claim requirements matches no
token at all**. For an entry meant to accept every repository, the requirement therefore has to be
something every token already satisfies.

Develocity's documentation suggests a requirement on the issuer or audience claim for exactly this
case. That advice is **half right**:

| Sole claim requirement | Result |
| --- | --- |
| `iss` **Equals** `https://token.actions.githubusercontent.com` | works — matches every GitHub Actions token |
| `aud` **Equals** `https://develocity.example.com` | **`HTTP 401` for every repository** |

The `aud` failure is not a configuration mistake. The tokens carried exactly that string as their
`aud`, the entry's own Audience field held the same value, and a repository that had been
publishing successfully moments earlier under a `repository` requirement began failing the instant
the requirement changed to `aud`. Nothing else differed.

Since `iss` works and both claims have their own dedicated field on the entry, the failure is
specific to `aud` rather than a general exclusion of standard claims.

The token itself narrows it further: GitHub sends `aud` as a **plain JSON string** (§7), so nothing
on the wire is an array. If the array-claims-are-absent rule is what defeats an `aud` requirement,
the array is introduced by Develocity's own JWT parsing — libraries commonly normalise `aud` to a
collection because the JWT spec permits either form, and `aud` is the only claim carrying that dual
typing. Hypothesis rather than confirmed cause, but it is now a hypothesis about the server's
parsing rather than about the token.

Workable match-all requirements, in preference order:

1. `iss` **Equals** the issuer URL — documented, states its intent plainly, and confirmed working.
2. `repository` **Contains** `/` — every `owner/repo` value contains a slash.
3. `repository` **Regular Expression** `.+` — equivalent to the above.

Related: the documentation lists "the dedicated string claims `repository_owner`, `repository`,
`ref`, `workflow_ref`, `job_workflow_ref`", which reads like an allowlist. It is not one —
`repository_id` is absent from that list and works, as does `iss`. The real constraint is the
claim's JSON type: strings match; numeric, boolean and array claims are treated as absent.

### 5.2 The fixed role is granted to every repository on GitHub

An all-repositories entry means any repository, in any account, can obtain a token. That is
acceptable **only** because an unregistered repository holds no project groups and can publish
nowhere — verified: a repository in an unrelated account authenticated successfully (`HTTP 200`)
and was denied publishing both to another repository's project and to its own unregistered one.

But that argument covers **project-scoped** actions only. Once project-level access control is
enforced, the project-scoped actions are: viewing Build Scan data, publishing Build Scans, Build
Cache read/write, Test Distribution, and Predictive Test Selection. Everything else a role can
carry is **not** project-scoped — API data access, administration, and "access all data without an
associated project" among them — and is held by any repository that asks for a token.

So the entry's role must be minimal and audited as a security boundary. No publishing test will
catch a problem here.

A related failure seen in practice: a correct project group grant sitting beside a **broad role**,
where the role silently widened access and the token published into a project its group did not
contain. The grant looked right; the extra capability came from the role next to it.

### 5.3 `DEVELOCITY_ACCESS_KEY` and the REST API want opposite forms

| Consumer | Wants | Given the other form |
| --- | --- | --- |
| `DEVELOCITY_ACCESS_KEY` (Gradle plugin) | `«host»=«key»` | build fails before the plugin applies: *value is malformed* |
| `Authorization: Bearer` (REST API) | the bare key | `HTTP 401` |

The host-qualified form exists so a build tool cannot send a credential to a server it was not
issued for; the REST API has no such notion and sees the whole string as one malformed token. So
the exchange must **re-attach** the host prefix to the token it receives, and any script
presenting a stored access key to the API must **strip** it.

Both directions mislead. A missing prefix makes a working exchange look like a publishing failure.
A present prefix makes the exchange endpoint look broken.

`setup-gradle` already handles this — it parses and produces the host-qualified form — so this
only bites hand-rolled exchanges (§4.4) and scripts talking to the REST API directly.

### 5.4 A refused publish does not fail the build

The Develocity plugin prints a warning and the build still reports `BUILD SUCCESSFUL`. **The exit
code carries no information about whether publishing was allowed.** Any test of access control
must assert on the server's message, and specifically:

| Situation | Server says |
| --- | --- |
| allowed | a scan URL is printed |
| no access to the named project | `denied the request to publish the build scan` |
| no project named, and unassociated data disallowed | `rejected the request due to a project ID being required` |

Note the third row is why a project id cannot be optional: a connected repository must name its
project, either in its build or via injection (§4.2). Deriving it from the repository id means
that happens without anyone configuring it.

Asserting merely that *no scan URL appeared* is not enough — that is satisfied by any failure at
all, including a broken credential, a misconfigured entry, or a compile error. A test written that
way reports successful isolation while proving nothing. Assert on the denial text, and treat
"nothing published, for an unaccounted reason" as **inconclusive** rather than a pass.

### 5.5 Misconfiguration fails closed, totally, and opaquely

An empty claim requirement list, a mistyped requirement value, an `aud` requirement, and a wrong
audience all produce the same single symptom: `HTTP 401` for every repository at once,
indistinguishable from a legitimately rejected token. Develocity cannot validate a requirement
value — it has no way to know your repository names — so a wrong-but-well-formed value looks
exactly like a correct one until a token fails to match.

Consequences worth designing for:

- The failure direction is safe: misconfiguration never silently over-grants. It stops everything.
- One hand-edited object, with no API and no validation, is a single point of total outage. Keep it
  in the configuration export, and have somebody read it before each change.
- Run an end-to-end check on a schedule. A positive case, a negative case, and a no-project case
  will detect this in minutes rather than via a report that builds stopped publishing.

### 5.6 Other things that bite

- **Project IDs are permanent.** Projects cannot be deleted. Decide the naming scheme before
  self-service registration starts creating them.
- **Enforcement and unassociated data.** Project-level access control must be enabled, and "allow
  data without an associated project" disabled — otherwise unregistered repositories can publish
  unassociated data, and the whole model quietly stops holding.
- **Token lifetime.** The Develocity token expires at its `exp`; exchange once per job rather than
  passing tokens between long-running steps.
- **Revocation is all-or-nothing.** `POST /api/auth/revoke-signing-keys` invalidates every
  outstanding access token. There is no per-workload revocation: editing the entry stops new
  tokens, but issued ones remain valid until they expire.
- **Do not scope on `sub`.** Its format changed for repositories created after 2026-07-15, so a
  rule written against the older format silently stops matching.
- **`Starts With` is a literal prefix check.** `repository` Starts With `acme/app` also matches
  `acme/app-fork`. Include the boundary character (`acme/app` → trailing `/` or `:` as
  appropriate), or use `Equals`.

## 6. Self-service registration

A registrant — typically a GitHub App — automates the per-repository objects in §3. Projects and
project groups **do** have a REST API (documented as Beta); workload identity entries do not,
which is what forces the single shared entry.

On registering a repository, the registrant creates, all keyed on the repository id (§3):

1. the project `«repository_id»`, display name set to the repository's full name,
2. the project group `«repository_id»` containing it, same display name,
3. the group's identity provider mapping, set to `«repository_id»`.

Requirements on the registrant:

- **Derive the repository from the installation**, never from user-supplied input. Accepting a
  repository name lets somebody register a repository they do not own. The App already receives
  the repository and its id in its webhook and API payloads, so no name lookup is needed and no
  input needs trusting.
- **Key everything on the repository id.** It is globally unique, unforgeable, and unchanged by
  renames and transfers. Should you use names instead, use the full `owner/repo` — never a bare
  repository name, which anybody could create under their own account and match — and accept that
  a rename then requires updating Develocity.
- **Record the human-readable name** in the group's display name or description, since the mapping
  value alone is opaque.
- **Emit the workflow snippet**, which needs only `id-token: write`, injection enabled and the
  server URL. The audience defaults to the server URL and the project id derives from the
  repository id, so neither has to be written in. Using Develocity injection (§4.2) keeps this to a
  workflow file and avoids touching the repository's build logic — which matters for onboarding,
  since a build-file change means opening a pull request against a repository the registrant does
  not own and waiting for somebody to merge it.
- **Treat the registrant's own Develocity credential as the most sensitive thing in the system.**
  Creating projects requires the *Configure projects* permission, so the registrant holds a
  long-lived administrative key — somewhat against the spirit of removing long-lived keys from CI.

Behaviour as repositories arrive:

| State | Result |
| --- | --- |
| registered, mapping matches | publishes to its own project, denied every other |
| authenticated but **not** registered | token issued, holds no project groups, publishes nowhere |
| registered but build names no project | refused: *a project ID being required* |
| registered, build does not reference Develocity at all | publishes, if injection supplies the plugin **and** the project id |
| never registered, unrelated account | token issued, publishes nowhere |

Registration is what grants capability. Until it happens the credential is inert, which is what
makes opening authentication to every repository tolerable.

## 7. Appendix: what a GitHub Actions OIDC token contains

Captured from a real token. Values are placeholders; **types and shapes are exact**. Header:

```json
{ "alg": "RS256", "kid": "«uuid»", "typ": "JWT", "x5t": "«thumbprint»" }
```

Payload — 31 claims. Everything is a JSON **string** except `exp`, `iat` and `nbf`, which are
integers:

```json
{
  "actor": "example-user",
  "actor_id": "179734",
  "aud": "https://develocity.example.com",
  "base_ref": "",
  "check_run_id": "98234261885",
  "event_name": "workflow_dispatch",
  "exp": 1787760411,
  "head_ref": "",
  "iat": 1787760111,
  "iss": "https://token.actions.githubusercontent.com",
  "job_workflow_ref": "example-org/example-repo/.github/workflows/build.yml@refs/heads/main",
  "job_workflow_sha": "«sha»",
  "jti": "«uuid»",
  "nbf": 1787759811,
  "ref": "refs/heads/main",
  "ref_protected": "false",
  "ref_type": "branch",
  "repository": "example-org/example-repo",
  "repository_id": "1234567890",
  "repository_owner": "example-org",
  "repository_owner_id": "317448489",
  "repository_visibility": "public",
  "run_attempt": "1",
  "run_id": "«run id»",
  "run_number": "5",
  "runner_environment": "github-hosted",
  "sha": "«sha»",
  "sub": "repo:example-org@317448489/example-repo@1234567890:ref:refs/heads/main",
  "workflow": "build",
  "workflow_ref": "example-org/example-repo/.github/workflows/build.yml@refs/heads/main",
  "workflow_sha": "«sha»"
}
```

Implications for claim requirements, since the matcher reads strings and treats other types as
absent:

| Claim | Type | Usable as a requirement |
| --- | --- | --- |
| `iss`, `repository`, `repository_owner`, `ref`, `ref_type`, `workflow_ref`, `job_workflow_ref` | string | yes — the documented set |
| `repository_id`, `repository_owner_id`, `actor_id`, `run_id`, `check_run_id`, `run_number`, `run_attempt` | string | yes — every identifier is a string, so ids are matchable |
| `repository_visibility`, `runner_environment`, `event_name`, `actor`, `sha`, `base_ref`, `head_ref` | string | yes — outside the documented list but equally usable |
| `ref_protected` | string `"false"` | yes — a string, not a boolean, despite reading like one |
| `aud` | string | **no** — see §5.1 |
| `exp`, `iat`, `nbf` | integer | no — numeric claims are treated as absent |

Two of these are worth a second look when hardening an entry:

- **`runner_environment`** is `github-hosted` or `self-hosted`. Requiring `github-hosted` stops an
  entry accepting tokens minted on self-hosted runners.
- **`repository_visibility`** is `public`, `private` or `internal`, which allows an entry to exclude
  public repositories.

And a warning the token makes concrete: **`sub` now embeds numeric ids** —
`repo:«owner»@«owner_id»/«repo»@«repo_id»:ref:«ref»` — for repositories created after 2026-07-15.
Any rule written against the older `repo:«owner»/«repo»:ref:«ref»` form silently stops matching, so
do not scope on `sub`.
