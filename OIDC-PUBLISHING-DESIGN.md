# Publishing Build Scans with GitHub OIDC and Project-Level Access Control

A design for letting any number of GitHub repositories publish Build Scans to Develocity with **no
stored Develocity credentials**, where each repository can publish only to its own project, and
onboarding a repository requires no change to the authentication configuration.

Everything here was established by running it against a Develocity server with project-level
access control and workload identity, and is written with placeholder names so it can be applied
to a new deployment.

Placeholders used throughout:

| Placeholder | Meaning |
| --- | --- |
| `https://develocity.example.com` | your Develocity server |
| `«repository_id»` | GitHub's numeric-but-string repository id, e.g. `1234567890` |
| `«project-id»` | the Develocity project a repository publishes to |
| `«group-id»` | the Develocity project group containing that project |

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
Gradle build publishes a Build Scan to «project-id»
  │
  └─ allowed only if the token holds a project group containing «project-id»
```

Three separate decisions, easy to conflate:

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
| Audience | `https://develocity.example.com` | Must equal what the workflow requests. GitHub defaults the audience to the repository owner URL, so pass one explicitly and make these agree. |
| Claim requirement | `repository` **Contains** `/` | Matches every repository while keeping the requirements list non-empty. See §5.1 — the obvious alternatives do not work. |
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

Per repository, three Develocity objects and one relationship:

1. A **project**, id `«project-id»`. Choose a scheme now: **projects cannot be deleted**, so every
   registration is permanent, including mistakes and repositories that later disappear.
2. A **project group**, id `«group-id»`, containing that project and nothing else.
3. That group's **identity provider mapping** set to the repository's `«repository_id»`.

A group's mapping holds exactly **one** value. Many-to-one is not supported: two claim values
cannot both map to one group. One-to-many is fine — one value may grant several groups.

Nothing else changes. The workload identity entry is untouched by onboarding, which is the point.

## 4. The workflow side

```yaml
permissions:
  contents: read
  id-token: write        # without this there is no OIDC token to exchange

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      - name: Exchange an OIDC token for a Develocity token
        env:
          DV: https://develocity.example.com
        run: |
          set -euo pipefail

          # 1. GitHub issues a token describing this job. It lasts ~5 minutes,
          #    which is why it is exchanged rather than used directly.
          oidc=$(curl -sS --fail-with-body \
            -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
            "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=${DV}" \
            | python3 -c 'import json,sys; print(json.load(sys.stdin)["value"])')
          echo "::add-mask::$oidc"

          # 2. Exchange it. Requesting no permissions or projectIds means the
          #    token carries exactly what the matching entry grants.
          code=$(curl -sS -o /tmp/tok -w '%{http_code}' -X POST \
            "$DV/api/auth/token?expiresInHours=1" \
            -H 'Content-Type: application/json' \
            -H "Authorization: Bearer $oidc" --data '')
          [ "$code" = "200" ] || { echo "exchange failed: $code $(cat /tmp/tok)"; exit 1; }
          tok=$(cat /tmp/tok)
          echo "::add-mask::$tok"

          # 3. The Gradle plugin requires the host-qualified form (see §5.3).
          host="${DV#*://}"
          echo "DEVELOCITY_ACCESS_KEY=${host}=${tok}" >> "$GITHUB_ENV"

      - run: ./gradlew build
```

And in `settings.gradle.kts`:

```kotlin
plugins {
    id("com.gradle.develocity") version "«latest»"
}

develocity {
    server = "https://develocity.example.com"
    projectId = "«project-id»"          // required; see §5.4

    buildScan {
        publishing.onlyIf { true }
        uploadInBackground = false      // let the upload finish before the runner exits
    }
}
```

Notes:

- **Request the audience explicitly.** One shared entry means one audience value, and GitHub's
  default is the repository owner URL, which will not match.
- **Do not narrow the exchange.** `/api/auth/token` accepts `permissions` and `projectIds`
  parameters. Passing them makes the request the thing under test rather than the entry's grants,
  and cannot increase access — the endpoint refuses to mint a token exceeding the credential
  presenting it. Omit them and let the entry decide.
- **Mask both tokens.** Neither should reach a log.

## 5. Pitfalls

Each of these cost real time, and several are indistinguishable from one another from the outside.

### 5.1 `aud` and `iss` do not work as claim requirements

An entry needs at least one claim requirement — **an entry with no claim requirements matches no
token at all**. Develocity's own documentation suggests that if you need to accept tokens with no
other criteria, you add a requirement on the issuer or audience claim.

**That does not work.** With a sole requirement of `aud` **Equals** `https://develocity.example.com`,
against tokens carrying exactly that string value for `aud`, every exchange returns `HTTP 401` —
including from a repository that had been publishing successfully moments earlier under a
`repository` requirement. Only `aud` was tested; `iss` may behave the same.

Use a requirement on a claim the matcher demonstrably reads, with a pattern that matches
everything:

- `repository` **Contains** `/` — every `owner/repo` value contains a slash.
- `repository` **Regular Expression** `.+` — equivalent.

Related: the documentation lists "the dedicated string claims `repository_owner`, `repository`,
`ref`, `workflow_ref`, `job_workflow_ref`", which reads like an allowlist. It is not one —
`repository_id` is absent from that list and works. The real constraint is the claim's JSON type:
strings match; numeric, boolean and array claims are treated as absent.

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

### 5.4 A refused publish does not fail the build

The Develocity plugin prints a warning and the build still reports `BUILD SUCCESSFUL`. **The exit
code carries no information about whether publishing was allowed.** Any test of access control
must assert on the server's message, and specifically:

| Situation | Server says |
| --- | --- |
| allowed | a scan URL is printed |
| no access to the named project | `denied the request to publish the build scan` |
| no project named, and unassociated data disallowed | `rejected the request due to a project ID being required` |

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

On registering a repository, the registrant creates:

1. the project `«project-id»`,
2. the project group `«group-id»` containing it,
3. the group's identity provider mapping, set to the repository's `«repository_id»`.

Requirements on the registrant:

- **Derive the repository from the installation**, never from user-supplied input. Accepting a
  repository name lets somebody register a repository they do not own. The App already receives
  the repository and its id in its webhook and API payloads, so no name lookup is needed and no
  input needs trusting.
- **Always write the full, unforgeable identifier** as the mapping value. `«repository_id»` is
  globally unique and cannot be forged. If you use names instead, use the full `owner/repo` —
  never a bare repository name, which anybody could create under their own account and match.
- **Record the human-readable name** in the group's display name or description, since the mapping
  value alone is opaque.
- **Emit the workflow snippet**, including the explicit audience. A repository that takes GitHub's
  default audience will not match the entry.
- **Treat the registrant's own Develocity credential as the most sensitive thing in the system.**
  Creating projects requires the *Configure projects* permission, so the registrant holds a
  long-lived administrative key — somewhat against the spirit of removing long-lived keys from CI.

Behaviour as repositories arrive:

| State | Result |
| --- | --- |
| registered, mapping matches | publishes to its own project, denied every other |
| authenticated but **not** registered | token issued, holds no project groups, publishes nowhere |
| registered but build names no project | refused: *a project ID being required* |
| never registered, unrelated account | token issued, publishes nowhere |

Registration is what grants capability. Until it happens the credential is inert, which is what
makes opening authentication to every repository tolerable.
