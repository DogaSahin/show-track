# ShowTrack

A personal anime and TV watch tracker, built for small closed groups — a household, a couple, a
friend group. You track what you're watching; the people you've invited see your progress, scores and
reviews, and you see theirs.

It is deliberately **not** a social network. There is no follow graph, no discovery of strangers, and
no public profiles. Membership of a group *is* the relationship, which is what makes the useful part
possible: because everyone in a group can see everyone's exact progress, the app can tell you who is
ahead on a show and how far.

**Status:** early. The backend schema and migrations, auth, the AniList/TMDB provider
integrations with unified search, the personal library — add, list, update, remove — AniList list
import, the background sync that keeps airing dates fresh, self-hosted push notifications, and
content-based recommendations, and **closed groups** — create, invite, join, leave, with ownership
that survives the owner walking out — are in place, and so is what a group is ultimately *for*: a
shared activity feed, reviews, a shared "we should watch this" watchlist, and side-by-side progress
on a title. The Android client is not built yet. See [Project status](#project-status).

## What it does

- **Track** anime and TV in one library — status, 1–10 score, episode progress, favourites.
- **Know when the next episode airs.** A background job refreshes airing dates on a schedule and
  queues a notification 24 hours before an episode airs, and again shortly before; a second job
  drains that queue to your phone. Push is delivered by a **self-hosted [ntfy](https://ntfy.sh)
  server**, not Firebase — see [Notifications](#notifications).
- **Import** an existing AniList list by username. **The profile must be public** — the import
  sends no credentials, so a private list is not readable and returns a 404. Read-only and
  one-way: ShowTrack never writes back to AniList.
- **Share with a group** — a feed of what members watched and rated, a shared "we should watch this"
  watchlist, side-by-side progress on titles you're both watching, and reviews. You get in by an
  **invite code**, which is also all a new housemate needs to create their account — see
  [Groups](#groups).
- **Get recommendations**, seeded from what AniList/TMDB report as similar to titles you rated
  highly (score 7 or higher), favourited or finished, and ranked by how well each candidate's
  genres match your own weighted taste profile. **Content-based, not collaborative filtering** —
  nothing about what anyone else watched feeds a suggestion. Anything already in your library is
  excluded, and every item says which title of yours it's because of — see
  [Recommendations](#recommendations).

Data comes from **AniList** (anime, GraphQL) and **TMDB** (TV, REST), normalised behind a single
provider interface so nothing downstream knows which one a title came from. `GET /v1/media/search`
fans out to both live, on the request path, with a per-provider timeout — a slow or down provider
degrades its own share of the results instead of failing the whole search. Every other read path
stays database-only.

## Layout

A monorepo with two independently-pipelined projects.

```
show-track/
├── backend/     FastAPI · SQLAlchemy 2.0 (async) · Alembic · PostgreSQL · httpx
├── android/     Kotlin · Jetpack Compose · Hilt · Retrofit · Room
│   └── build-logic/  convention plugins, and the module rules they enforce
├── .github/     backend-ci · android-ci · gitleaks
└── .githooks/   commit-msg · pre-commit
```

They live together because they share one source of truth for the API contract between them: a change
to an endpoint and the client that calls it lands as a single reviewable unit instead of two repos
with a version-skew problem.

**Backend module pattern.** Each domain is four files — `models.py`, `schemas.py`, `service.py`,
`routes.py` — across `users`, `media`, `library`, `sync`, `notifications`, `recommendations`, and
`groups`. All routes mount under `/v1`; `/health` stays unversioned, because it is an infrastructure
probe rather than client contract.

**Android module pattern.** Feature-first and multi-module: `:app`, six `:core:*` (`model`,
`designsystem`, `navigation`, `network`, `database`, `data`) and one `:feature:*` per screen
(`auth`, `library`, `detail`, `discover`, `favorites`, `profile`, `search`, `groups`, `feed`).
Feature modules never depend on each other, and never on `:core:network` or `:core:database` — all
data access goes through `:core:data`, which is the only module that knows Retrofit and Room exist.
That is what keeps "Room is a cache, never the source of truth" structural rather than a convention
that erodes.

`:core:network` owns the HTTP stack: Retrofit over OkHttp with kotlinx.serialization, DTOs that match
the wire and are mapped to domain models further out, and the token lifecycle. Two clients share one
connection pool but not a dispatcher — the token endpoints are served by a client carrying neither the
auth interceptor nor the authenticator, because a refresh issued on the authenticated client would
re-enter the authenticator and deadlock behind its own lock. That authenticator refreshes once per
expiry no matter how many requests 401 together (the backend rotates refresh tokens, so parallel
refreshes would invalidate each other and log the user out mid-session) and gives up after one replay
rather than retrying forever. Tokens live in DataStore under AES-GCM with a key from the Android
Keystore; a terminal refresh failure clears them and emits `AuthEvent.LoggedOut`, and so does a
replayed request that 401s again — dead credentials are cleared rather than left to be refreshed
forever. Neither the interceptor nor the authenticator will attach a credential to a host that is
not the API's, so the authenticated client stays safe to share with something that fetches images
from a third-party CDN.

`:core:database` is the Room cache the module-dependency rule above exists to protect: one table,
`library_entries`, holding exactly what the library list screen renders — never the full
`:core:model.LibraryEntry` (which nests `Media`) and never a mirror of `LibraryEntryDto`. Room only
ever answers a cold start's first frame; every successful fetch overwrites the table in full through
`LibraryDao.replaceAll`, whose `@Transaction` matters for the same reason the network client's
dispatcher split does — without it, a crash between clearing the table and repopulating it leaves the
next cold start with nothing instead of yesterday's data. `score` stays a raw `String?` in the entity
too, for the same `NUMERIC(3,1)`-precision reason as the DTO; `updated_at` stores as INTEGER epoch
millis (`Converters` maps it to/from `java.time.Instant`) so `LibraryDao.observeAll`'s
`ORDER BY updated_at DESC` is a numeric comparison rather than one over a formatted string. The module
depends on nothing else in the monorepo, not even `:core:model` — mapping between this table and
either the wire or the domain shape is entirely `:core:data`'s job.

Both rules are **enforced by the build**, not by review. Both checks live in one of two "library"
convention plugins — `showtrack.android.library` for Android modules, or the pure-Kotlin/JVM
`showtrack.jvm.library` for a module like `:core:model` that must stay importable without pulling in
Retrofit or Room — which every module applies except `:app`, which uses
`showtrack.android.application` and needs neither check: both rules return `null` for it, since it
can never be a `:feature:*` consumer or a `:core:*` producer. Everywhere the checks do apply,
`library`/`jvm.library` carry the identical two of them, so applying `library`/`jvm.library` (plus
`compose`, for the Android modules that use it) by hand cannot opt out. Each inspects its own
project's project-dependencies and fails configuration on a violation, naming the two modules and the
reason. A third check closes the same rule from the export side: a `:core:*` module may not put
`:core:network` or `:core:database` on its `api` configuration, since one `api` edge would re-export
Retrofit or Room to every feature while every declared dependency in the build stayed legal. (For
`:core:model`, only the export-side check can ever fire — it can never be a `:feature:*` consumer, so
the first check is unreachable there by construction, not disabled.)

The rules themselves are pure functions (`build-logic/.../ModuleRules.kt`) with unit tests, and
Gradle TestKit tests drive a real build into each violation to prove the guards are actually reached
— a guard nobody has watched fail is indistinguishable from one that is never invoked.

Shared build configuration lives in the `build-logic` **included build** rather than `buildSrc`,
which would invalidate every build script on any change to it. It publishes six plugin ids:
`showtrack.android.application`, `.library`, `.compose`, `.feature`, `.hilt`, and
`showtrack.jvm.library`. Test dependencies are declared there, once, instead of per module —
`showtrack.jvm.library` ships its own JVM-appropriate junit/coroutines-test/turbine set for the same
reason.

`showtrack.android.hilt` carries the whole annotation-processing setup — KSP, the Hilt Gradle plugin,
`hilt-android` and the Hilt compiler — for the modules that need a dependency graph. Two constraints
on this toolchain are worth knowing before adding a seventh module to it. KSP needs
**`android.disallowKotlinSourceSets=false`** in `android/gradle.properties`: AGP 9 owns Kotlin and
otherwise rejects the `kotlin.sourceSets` DSL that KSP registers its generated sources through
(there is no AGP built-in KSP to use instead). And the Hilt Gradle plugin must be **2.60 or newer** —
2.57 looks up AGP's `BaseExtension`, which AGP 9 removed, and fails to apply at all.

One sharp edge lives there too, and it is Android-only. AGP 9 provides Kotlin itself and refuses to
run alongside the Kotlin Android Gradle plugin, and `ktlint-gradle` registers its source-set tasks
only when *that* plugin is applied — so out of the box `ktlintCheck` lints build scripts and not one
line of Kotlin. `showtrack.android.library`/`.application` widen the tasks it does register to cover
`src/**/*.kt`, and a TestKit test drives a malformed Kotlin file through a real build so the check
cannot go quietly inert again. `showtrack.jvm.library` needs none of this: a pure-Kotlin/JVM module
applies the Kotlin Gradle Plugin directly, which is the exact plugin id `ktlint-gradle` listens for,
so it registers its source-set tasks natively — carrying the widening there anyway would be a
workaround copied into a module that was never broken. A second TestKit test proves that natively-
registered task actually catches a malformed file, and a third proves the export-side architecture
check is live for a `:core:*` module that applies `showtrack.jvm.library`, not just the Android one.

A Kotlin JVM module also needs one more line that an Android module gets for free: Gradle names a
plain `kotlin.jvm` module's test task `test`, not `testDebugUnitTest`, so the root
`testDebugUnitTest` lifecycle task's name-matching would silently skip it without an explicit
`testDebugUnitTest { dependsOn(test) }` alias — added in `showtrack.jvm.library`, and it is the
reason `:core:model`'s tests run in the same gate command as every other module's.

## Getting started

### Prerequisites

- [`uv`](https://docs.astral.sh/uv/) for the backend's Python environment
- Docker and Docker Compose (PostgreSQL 17)
- JDK 21 for Android

### Backend

```bash
cd backend
uv venv --python 3.12      # pinned: CI, the Dockerfile and ruff's target-version all say 3.12,
                           # and a bare `uv venv` picks whatever newest Python it can find
uv pip install -r requirements-dev.txt

cp .env.example .env          # fill in TMDB_API_KEY if you have one; every other value has a
                               # working default or is already filled in
docker compose up -d db       # PostgreSQL on :5432

.venv/bin/alembic upgrade head    # REQUIRED before running the tests
.venv/bin/uvicorn main:app --reload --port 8000
```

`DATABASE_URL` has no default — an unset value fails loudly at startup rather than silently
connecting to a plausible-looking wrong database. `alembic upgrade head` is not optional before
running tests: the suite runs against a real PostgreSQL schema built by the migrations, never by
`metadata.create_all()`, so the migrations themselves are exercised rather than merely stored.

**Settings** (`.env`, copied from `.env.example`):

- `DATABASE_URL`, `SECRET_KEY`, `REGISTRATION_CODE` — required, no default; the app fails loudly at
  startup rather than falling back to something plausible-looking but wrong. `REGISTRATION_CODE` is
  the **bootstrap** account path, not the only one — a group's invite code registers an account too;
  see [Groups](#groups).
- `TMDB_API_KEY` — **optional**. Without it, `/v1/media/search` returns AniList (anime) results only
  and reports `not_configured` for TMDB. AniList itself needs no key at all — that half works with
  zero signup.
- `LOG_LEVEL` (default `INFO`), `ENVIRONMENT` (default `local`) — optional, both already set in
  `.env.example`.
- `ACCESS_TOKEN_TTL_MINUTES` (default `30`), `REFRESH_TOKEN_TTL_DAYS` (default `30`) — optional, not
  in `.env.example` since the defaults are fine to start with; set them to override.
- `NTFY_BASE_URL`, `NTFY_TOKEN`, `NOTIFICATION_DISPATCH_MINUTES` — **optional**, and covered in
  [Notifications](#notifications). An unset `NTFY_BASE_URL` disables push cleanly: the dispatch job
  is never registered and notification tasks queue as `pending` rather than erroring.
- `GROUP_INVITE_TTL_HOURS` (default `168`, i.e. seven days) — **optional**, already set in
  `.env.example`. How long a group's invite code stays usable. Because that code also registers an
  account, this is the window in which your server accepts a new signup on the strength of it.
- `RECOMMENDATIONS_SEED_HOURS` (default `12`), `RECOMMENDATIONS_TTL_HOURS` (default `24`) —
  **optional**, both already set in `.env.example`, and covered in
  [Recommendations](#recommendations).

Every route except `/v1/auth/*` and `/health` requires a bearer access token, so the first working
request is registering and logging in.

**`invite_code` has two meanings, and either one registers you.** It is either the
`REGISTRATION_CODE` from your `.env` — the server-wide bootstrap code, which creates an account and
nothing else — or **any group's invite code**, which creates the account *and* joins you to that
group in the same request. That is the whole difference between a server nobody else can join and
one your household can: you do not have to hand out the server secret to add a housemate, you hand
out a code that is scoped to one group and expires. A wrong, unknown or expired code — or one whose group was
deleted while you were joining — all answer the same `400 invalid invite code`, on purpose — an expired code that said so would confirm the group
exists and that you were merely too late.

The first account has to use `REGISTRATION_CODE`, because there is no group yet to be invited to:

```bash
# invite_code is the REGISTRATION_CODE from your .env.
curl -s -X POST localhost:8000/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"me","email":"me@example.com","password":"change-this-password","invite_code":"change-me"}'

TOKEN=$(curl -s -X POST localhost:8000/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"change-this-password"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8000/v1/media/search?q=frieren'

# add a search result to your library, by the (source, external_id) it came back with
curl -s -X POST localhost:8000/v1/library \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"source":"anilist","external_id":"154587"}'

# read it back — soonest-airing first, 20 per page
curl -s -H "Authorization: Bearer $TOKEN" \
  'localhost:8000/v1/library?sort=next_episode_date&limit=20'

# rate it, and mark how far you have got
ENTRY=$(curl -s -H "Authorization: Bearer $TOKEN" localhost:8000/v1/library \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["id"])')
curl -s -X PATCH "localhost:8000/v1/library/$ENTRY" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"score":8.5,"progress":12,"status":"watching"}'
```

```bash
# import a public AniList profile — read-only, one-way, and local edits always win
curl -s -X POST localhost:8000/v1/library/import/anilist \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"your-anilist-username"}'
# -> {"imported": 412, "skipped": 0, "failed": 0, "truncated": false}
```

Re-running an import inserts only titles missing from your library and **never overwrites a score
or progress set in ShowTrack** — that is a database constraint, not a code path, so it cannot
regress. `failed` counts entries AniList returned that could not be mapped (an unrecognised
status, a malformed entry); `truncated` is true if the list was longer than the 10,000-entry
ceiling and only its first chunk-run was imported. A private or nonexistent username returns
**404**, not an error about the server.

Adding a title you already track returns **200** with the existing entry rather than an error, and
changes nothing — so a retry after a dropped connection is safe. `score` travels as a JSON string
(`"8.5"`) because the column is `NUMERIC(3,1)`: a JSON number is a float, and floats cannot hold
8.5-style values exactly. `/v1/library` is cursor-paginated — follow `next_cursor` until it is
`null`, and do not change `sort` while paging (the cursor is bound to the sort it was issued for
and answers 400 otherwise).

Without a `TMDB_API_KEY`, that last response carries `"sources":{"anilist":"ok","tmdb":"not_configured"}`
alongside AniList results — the degradation contract (§8 of the design doc) working, not just documented.
`/docs` (Swagger UI) is the interactive alternative to curl for exploring the rest of the API.

```bash
# recommendations — content-based, not collaborative filtering: each candidate is something a
# provider calls similar to a title you rated highly, favourited or finished, ranked by how well
# its genres match your own weighted taste profile; anything already in your library is excluded
curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8000/v1/recommendations?limit=2'
# -> {"items":[{"media":{"id":"...","source":"anilist","external_id":"140960","type":"anime",
#                        "title":"...","year":2023,"genres":["fantasy","drama"],"cover_image_url":"..."},
#               "reason":{"seed_media_id":"...","seed_title":"Frieren: Beyond Journey's End","matched_genres":["fantasy","drama"]}},
#              ...],
#     "next_cursor":"eyJrIjoicmFuayIsInYiOiI0MiIsImkiOiIuLi4ifQ=="}
#
# `media` here is narrower than the one inside a /v1/library entry: no `status`, no next-episode
# block. Those are refreshed by the sync job, whose worklist covers only titles that are in
# somebody's library — which a recommendation candidate, by definition, is not. Rather than serve
# a frozen air date that renders as "airs today" forever, the fields are simply not there.
# `id` is, so you can POST the candidate straight to /v1/library.

# page 2 — pass next_cursor straight back; it is opaque, not something to construct by hand
CURSOR=$(curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8000/v1/recommendations?limit=2' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["next_cursor"])')
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8000/v1/recommendations?limit=2&cursor=$CURSOR"
```

A cold library — nothing rated highly, favourited or finished yet — gets back `{"items":[],
"next_cursor":null}` rather than an error, and so does a qualifying one the seed job has not
reached yet: candidates come only from that job, never from this request. **Its schedule works
against a reader following this walkthrough in order.** A freshly started server's first seed run
lands about five minutes after startup — quite possibly before you finish registering, searching
and rating something above — so it can run once against a library with nothing to seed from, and
then not again for `RECOMMENDATIONS_SEED_HOURS` (default 12; `ge=1` means it cannot be pushed
below an hour). Unlike sync, there is **no** `POST /v1/debug/*` to force it early, so **rate
something first, then restart the backend** — a fresh process re-arms the same five-minute offset
— and wait those five minutes. (Why five, when the airing sync waits one: both jobs share a single
memoised AniList rate limiter, so they are staggered rather than co-located; see
[Recommendations](#recommendations).) The alternative is waiting out the full interval without
restarting — but `$TOKEN` above is an **access** token, whose default TTL is 30 minutes, so on that
path **log in again first** or every curl comes back `401` and the feature looks broken when only
the token was.

The job also only exists at all when `SYNC_ENABLED` is `true`, the gate that sync, the threshold
scan and dispatch share. On **the single node you are following this walkthrough with**, leaving it
`false` means recommendations stay empty forever rather than merely arriving late. That is scoped
deliberately: on an *extra replica* `SYNC_ENABLED=false` is the documented, intended setting — see
[Background sync](#background-sync).

See [Recommendations](#recommendations) for how a candidate is chosen, why `reason` names only one
title, and why there is no score field to sort by yourself.

Now the second account. `$TOKEN` below is still account one's, from the first block:

```bash
# 1. account one creates a group. The creator is the owner — that and an owner leaving are the
#    only two ways to become one, so no code can ever mint an owner.
CODE=$(curl -s -X POST localhost:8000/v1/groups \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Household"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["invite_code"])')
echo "$CODE"   # -> e.g. H7K2QM9XTB43 — 12 characters, and a credential: share it like one

# 2. account two registers WITH THAT CODE. No REGISTRATION_CODE, no prior account, one request.
curl -s -X POST localhost:8000/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"housemate\",\"email\":\"housemate@example.com\",\"password\":\"another-password\",\"invite_code\":\"$CODE\"}"

# 3. it is already a member — no join step. Log in as account two and list its groups.
MATE=$(curl -s -X POST localhost:8000/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"housemate@example.com","password":"another-password"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -s -H "Authorization: Bearer $MATE" localhost:8000/v1/groups
# -> [{"id":"...","name":"Household","created_at":"..."}]   note: no invite_code in this shape

# 4. account one sees both members and their roles
GROUP=$(curl -s -H "Authorization: Bearer $TOKEN" localhost:8000/v1/groups \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8000/v1/groups/$GROUP/members"
# -> [{"user_id":"...","username":"me","role":"owner","joined_at":"..."},
#     {"user_id":"...","username":"housemate","role":"member","joined_at":"..."}]

# 5. the other door: an account that already exists joins with a code. Idempotent — account two
#    is already a member, so re-pasting the code answers 200 with the group, not an error.
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8000/v1/groups/join \
  -H "Authorization: Bearer $MATE" -H 'Content-Type: application/json' \
  -d "{\"invite_code\":\"$CODE\"}"   # -> 200

# 6. someone forwarded the code to a group chat? Rotate it. The old code is dead immediately, for
#    everyone, and the new one gets a fresh GROUP_INVITE_TTL_HOURS window. Owner only.
curl -s -X POST -H "Authorization: Bearer $TOKEN" "localhost:8000/v1/groups/$GROUP/invite/rotate" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["invite_code"], d["invite_code_expires_at"])'
# $CODE is now worthless — reusing it answers 400, the same body a code that never existed gets

# 7. give the next four steps something to show: the housemate tracks the same title, and gets
#    four episodes in.
MATE_ENTRY=$(curl -s -X POST localhost:8000/v1/library \
  -H "Authorization: Bearer $MATE" -H 'Content-Type: application/json' \
  -d '{"source":"anilist","external_id":"154587"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s -X PATCH "localhost:8000/v1/library/$MATE_ENTRY" \
  -H "Authorization: Bearer $MATE" -H 'Content-Type: application/json' \
  -d '{"progress":4,"status":"watching"}'

# 8. the feed — what members did, newest first. There are no per-group activity rows: membership
#    is resolved at read time, so joining shows the history at once and leaving revokes it at once.
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8000/v1/groups/$GROUP/feed?limit=3"
# -> {"items":[{"id":"...","actor":{"id":"...","username":"housemate"},"kind":"progressed",
#               "media":{...},"payload":{"status":"watching","progress":4},"created_at":"..."},
#              {"id":"...","actor":{"id":"...","username":"housemate"},"kind":"added",
#               "media":{...},"payload":{},"created_at":"..."},
#              {"id":"...","actor":{"id":"...","username":"me"},"kind":"imported",
#               "media":null,"payload":{"count":412},"created_at":"..."}],
#     "next_cursor":"eyJrIjoiY3JlYXRlZF9hdCIsInYiOiIyMDI2LTA4..."}
#
# `media` is null on that last item and a client has to handle it: an import is ONE line about N
# titles, not N lines. That line is there only if you ran the AniList import above; skip it and the
# third row is your own "rated" instead. `kind` is one of added, imported, progressed, rated,
# completed, dropped.

# page 2 — the same opaque-cursor contract as /v1/library and /v1/recommendations
CURSOR=$(curl -s -H "Authorization: Bearer $TOKEN" "localhost:8000/v1/groups/$GROUP/feed?limit=3" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["next_cursor"])')
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8000/v1/groups/$GROUP/feed?limit=3&cursor=$CURSOR"
# -> the two older rows, both yours: kind "rated", carrying the whole change set that PATCH sent
#    ({"score":"8.5","status":"watching","progress":12}) — one row per request, named for the most
#    significant field in it — and then kind "added". next_cursor is null: that was the last page.

# 9. reviews. One per person per title, edited rather than appended: a second POST for a title you
#    have already reviewed is a 409, and PATCH is how you change your mind. `media_id` is a MEDIA
#    id — the `id` inside a library entry's `media`, not the entry's own id. Adding a title you
#    already track is idempotent and hands the entry back, which is the shortest way to get one.
MEDIA=$(curl -s -X POST localhost:8000/v1/library \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"source":"anilist","external_id":"154587"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["media"]["id"])')
REVIEW=$(curl -s -X POST localhost:8000/v1/reviews \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"media_id\":\"$MEDIA\",\"body\":\"The best thing I have watched all year.\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s -X PATCH "localhost:8000/v1/reviews/$REVIEW" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"contains_spoilers":true}'
# -> {"id":"...","author":{"id":"...","username":"me"},"media_id":"...",
#     "body":"The best thing I have watched all year.","contains_spoilers":true,
#     "created_at":"...","updated_at":"..."}

# the group's reviews of that one title, oldest first — everyone's, including your own
curl -s -H "Authorization: Bearer $MATE" \
  "localhost:8000/v1/groups/$GROUP/media/$MEDIA/reviews"

# 10. the shared watchlist. Proposing is IDEMPOTENT and answers 200, not 201: two housemates
#     proposing the same show is agreement, not a conflict.
curl -s -o /dev/null -w '%{http_code}\n' -X POST "localhost:8000/v1/groups/$GROUP/watchlist" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"media_id\":\"$MEDIA\"}"   # -> 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST "localhost:8000/v1/groups/$GROUP/watchlist" \
  -H "Authorization: Bearer $MATE" -H 'Content-Type: application/json' \
  -d "{\"media_id\":\"$MEDIA\"}"   # -> 200, and the list still has exactly one entry
curl -s -H "Authorization: Bearer $MATE" "localhost:8000/v1/groups/$GROUP/watchlist?limit=20"
# -> {"items":[{"id":"...","media":{...},"proposed_by":"...","created_at":"..."}],
#     "next_cursor":null}

# any member may remove any entry: it is one shared list, not a pile of personal ones
ITEM=$(curl -s -H "Authorization: Bearer $MATE" "localhost:8000/v1/groups/$GROUP/watchlist" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["id"])')
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $MATE" \
  "localhost:8000/v1/groups/$GROUP/watchlist/$ITEM"   # -> 204

# 11. who is ahead on one title. A plain list, not a cursor page — it is bounded by membership.
#     Progress descending, ties broken by username, and the numbers are RAW: nothing is clamped to
#     an episode count, because ShowTrack does not reliably know one.
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8000/v1/groups/$GROUP/media/$MEDIA/progress"
# -> [{"member":{"id":"...","username":"me"},"status":"watching","progress":12},
#     {"member":{"id":"...","username":"housemate"},"status":"watching","progress":4}]

# 12. leaving. Anyone may remove themselves; only the owner may remove anybody else — and if the
#    OWNER leaves, ownership transfers to the longest-standing remaining member rather than the
#    group being left ownerless.
ME=$(curl -s -H "Authorization: Bearer $MATE" localhost:8000/v1/users/me \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $MATE" \
  "localhost:8000/v1/groups/$GROUP/members/$ME"   # -> 204
```

Codes are 12 characters of Crockford base32 — no `I`, `L`, `O` or `U`, and `O`/`I`/`L` typed by
mistake are folded onto `0`/`1`/`1`, so a code read off a screen and typed on a phone works. Case,
spaces, hyphens, underscores, line breaks and the non-breaking space a chat client substitutes are
all ignored: `h7k2-qm9x-tb43` is the same code, and so is one pasted straight out of a message with
the newline still attached. See [Groups](#groups) for expiry, rotation and what happens when the
owner leaves.

**Accepting an invite code shows the other members your whole library** — every title in it, your
progress, your scores and your reviews — and there is no per-title opt-out. Leaving the group is the
only way to take it back. That is the single most consequential thing to understand before pasting a
code, and the reasoning behind it, along with what the feed deliberately leaves out, is in
[Groups](#groups).

New migrations:

```bash
.venv/bin/alembic revision --autogenerate -m "..."
```

Always open the generated file and read it against the model before committing. Autogenerate is a
starting point, not an output to trust unread — notably, it cannot see a changed enum value set.

**Recorded provider fixtures.** `backend/tests/fixtures/{anilist,tmdb}/*.json` are upstream responses
captured by hand and committed; no test ever calls a live API. Two of them are the providers' published
genre lists, and `tests/test_genre_mapping.py` checks the tables in `app/media/providers/genres.py`
against *those recordings* rather than against the live endpoints — so an upstream adding a genre
cannot turn CI red on its own schedule, and the tables only ever move as a deliberate commit.

Re-record when an upstream changes its list:

```bash
# TMDB — record from an English response; TMDB localises genre names, which is why the table is
# keyed by integer id.
curl -s "https://api.themoviedb.org/3/genre/tv/list?api_key=$TMDB_API_KEY&language=en-US" \
  > backend/tests/fixtures/tmdb/genre_tv_list.json

# AniList — no key needed.
curl -s https://graphql.anilist.co -H 'Content-Type: application/json' \
  -d '{"query":"{ GenreCollection }"}' > backend/tests/fixtures/anilist/genre_collection.json
```

Then run `pytest`. A newly published genre fails `test_*_table_matches_the_published_list_exactly`
until it is added to `genres.py` — mapped to a canonical genre, or mapped to nothing on purpose, in
which case `test_only_the_deliberately_excluded_genres_map_to_nothing` has to name it too.

### Android

```bash
cd android
./gradlew build      # or just open the folder in Android Studio
```

The Android SDK location comes from `local.properties` (`sdk.dir=...`) or from `ANDROID_HOME` /
`ANDROID_SDK_ROOT`, which is what CI sets. The `build-logic` TestKit tests need it too, because they
configure a real Android build in a temp directory.

Local secrets — the TMDB API key, the ntfy server URL and credentials — go in `local.properties` or
a gitignored config file. Never in a committed Gradle file.

The app talks to the backend at `http://10.0.2.2:8000/` by default — the emulator's alias for the host
loopback, which is where `docker compose up` in `backend/` puts it. Point it elsewhere with a Gradle
property, either on the command line or in `android/gradle.properties`:

```bash
./gradlew assembleDebug -Pshowtrack.apiBaseUrl=http://192.168.1.10:8000/
```

Cleartext HTTP has been blocked by default since API 28, so `:core:network` ships a network-security
config permitting it for `10.0.2.2`, `localhost` and `127.0.0.1` — in its **debug** source set only,
where it cannot reach a release build.

### Git hooks

Once per clone:

```bash
git config core.hooksPath .githooks
```

This enables a Conventional Commit message check, ruff on staged Python files, and two layers of
credential guarding. Installing [`gitleaks`](https://github.com/gitleaks/gitleaks) is strongly
recommended — without it the content scan is skipped locally and only the filename guard runs.

## The gate

Run before every push. CI runs the same checks, but the local gate is the real one — branch
protection is not enabled.

**Backend**, from `backend/`:

```bash
.venv/bin/ruff check .
.venv/bin/ruff format --check .
.venv/bin/alembic check      # fails if models and migrations have drifted apart
.venv/bin/pytest
```

**Android**, from `android/`:

```bash
./gradlew ktlintCheck detekt
./gradlew testDebugUnitTest       # also runs the build-logic convention-plugin tests
./gradlew assembleDebug assembleDebugAndroidTest
```

`assembleDebugAndroidTest` compiles the instrumentation tests but does not run them — there is no
emulator in this environment or in CI. `:core:network` has one (the Android Keystore has no
off-device implementation, so whether it accepts the app's key spec is only answerable on a device);
`:core:database` has one too (Room DAO tests need a real SQLite, which the JVM unit-test classpath
doesn't have). Run either by hand against a connected device or emulator —
`./gradlew :core:network:connectedDebugAndroidTest` or
`./gradlew :core:database:connectedDebugAndroidTest` — the gate compiles both so neither can rot
between those runs.

## Contributing

- Work on a branch off `dev`. Releases go `dev` → `main`, tagged CalVer `vYYYY.0M.MICRO`.
- **Conventional Commits**, enforced by the commit-msg hook.
- Tests are required for new logic and bugfixes. Write useful tests, not exhaustive ones — a test
  earns its place by catching a real regression in behaviour we own, not by raising a number.
- Every change carries its CI updates, its README updates, and its `.gitignore` updates with it.

#### Background sync

Two jobs run inside the API process:

| Job | Interval | Cost |
|---|---|---|
| Airing refresh | `SYNC_INTERVAL_HOURS` (default 1) | queries AniList/TMDB, rate-limited |
| Threshold scan | `THRESHOLD_SCAN_MINUTES` (default 15) | database only, no network |

They are split because evaluating "airs within 24 hours" never needed a provider call. The scan
therefore runs often enough to make notification timing accurate to about a quarter of an hour,
and it keeps working during a provider outage — dates go stale, but the dates already known still
notify.

**The airing refresh does not poll every title on every run.** Neither provider offers webhooks, so
polling is the only option available — but a flat interval is the wrong shape for it, spending the
same budget on a show premiering in four months as on one airing tonight. Each title is instead
refreshed on a cadence set by how close its next episode is (`SYNC_TIERS` in
`app/sync/service.py`):

| Next episode airs | Refreshed at most every |
|---|---|
| within 48 hours, **or already in the past** | 1 hour |
| within 7 days | 6 hours |
| **no known date at all** (still airing) | 6 hours |
| later | 24 hours |

An air date stuck in the past counts as imminent on purpose — it is the strongest signal that the
stored pointer is stale. A title that has never been synced is always due, so nothing waits a tier
interval to be picked up the first time.

**No known date is its own tier, not the slow one.** AniList reports no next episode during a
mid-season break or a delay announcement, not only after a finale, and while the date is missing
the threshold scan cannot queue anything at all. Leaving those titles on the 24-hour cadence
means a show that loses its pointer a day before an airing can miss both notifications for that
episode with nothing in any log to say so. "We lost the pointer on a show that is still airing"
and "airs in three weeks" are not the same confidence.

Net effect against a flat 6-hourly sweep: **fewer** provider requests overall, because the long
tail drops from four a day to one, and far more of them aimed at the titles whose dates are about
to drive a notification. `SYNC_INTERVAL_HOURS` sets how often the job wakes up to check what is
due — raising it above the tightest tier silently widens every tier to that value, since the job
cannot refresh a title it is not awake to look at.

**Running more than one replica.** The scheduler is in-process, so every replica would otherwise
run both jobs and queue duplicate notifications. Two things prevent that:

- Each job takes a **Postgres advisory lock** before doing anything. A second instance that finds
  the lock held returns `ran: false` immediately rather than waiting.
- Set **`SYNC_ENABLED=false`** on the extra replicas. The lock is the safety net; this is the
  intent.

One deployment caveat, because it is invisible until it bites: these are **session-scoped**
advisory locks, which are incompatible with **PgBouncer in transaction-pooling mode** — the lock
would be taken on one backend and the unlock issued on another. That is the default pooler mode on
several managed Postgres providers, so check it before putting one in front of this.

`POST /v1/debug/sync` runs the airing refresh immediately, taking the same lock, so you can test
without waiting hours.

#### Notifications

A third job, on its own advisory lock. The split is the whole design: the **threshold scan** only
ever *queues* — it writes a `notification_tasks` row and never touches the network — and the
**dispatcher** only ever *drains*. Nothing is sent from the sync job, which is what makes a
provider outage unable to produce a wrong push, and a duplicate scan unable to produce a duplicate
push (a unique constraint on the queue does that, not application logic).

| Job | Interval | Lock key |
|---|---|---|
| Threshold scan — queues tasks | `THRESHOLD_SCAN_MINUTES` (default 15) | 5000002 |
| Dispatcher — sends and finalises | `NOTIFICATION_DISPATCH_MINUTES` (default 1) | 5000003 |

The scan **only queues for a user who has a registered device**, not merely one who has push
enabled. Queuing is irreversible — the dedup constraint ignores status, so a task the dispatcher
terminates for having nowhere to send can never be queued again for that episode. Since the setup
below has you enable push first and register a device second, queuing in that gap would silently
cost you every notification already in window. Registering a device part-way through a window
means waiting for the next scan (up to `THRESHOLD_SCAN_MINUTES`), which is a delay rather than a
loss.

The dispatcher **re-checks every task against the world before sending it**, because a queued task
is a decision made in the past. If the episode was rescheduled, the title was removed from your
library, or you turned push off in the meantime, the task terminates as `skipped` instead of
delivering something untrue. If it is simply too late to be useful, it terminates as `expired`. A
notification that arrives thirty hours after "airs in 24 hours" is misinformation, not a degraded
success.

Push is delivered by a **self-hosted [ntfy](https://ntfy.sh) server**, which is in the compose
file. With `NTFY_BASE_URL` unset there is no transport at all: the dispatch job is never
registered, tasks queue as `pending`, and nothing errors — so you can run the whole backend and
the whole test suite without any of this.

##### Setup

```bash
cd backend
docker compose up -d ntfy      # ntfy on :8080
```

The compose service runs with `NTFY_AUTH_DEFAULT_ACCESS: deny-all`, so **nobody can publish or
subscribe without credentials — including this backend**. Following the rest of this section
without minting a token gives you a server that rejects its own pushes with `403`. Create a
publisher and a token for it:

```bash
# Prompts for a password. Write-only on every topic, deliberately not --role=admin: the backend
# never needs to READ a notification stream, so a leaked NTFY_TOKEN cannot be used to eavesdrop.
docker compose exec ntfy ntfy user add showtrack
docker compose exec ntfy ntfy access showtrack '*' wo
docker compose exec ntfy ntfy token add showtrack     # prints tk_... — this is NTFY_TOKEN
```

**That user and token live in the `ntfy-data` volume, not in your `.env`.** `docker compose down -v`
destroys the volume and takes them with it, after which the token in `.env` starts coming back `403`
with nothing in the app naming the cause — it looks exactly like a mistyped token. The fix is to
re-run all three commands above and put the new token in `.env`. Plain `docker compose down`, without
`-v`, keeps them.

Now **edit the two lines `.env.example` already gave you** — `NTFY_BASE_URL=` and `NTFY_TOKEN=`, both
shipped empty — and fill them in **in place**:

```bash
NTFY_BASE_URL=http://localhost:8080     # edit the existing line; do not append a second one
NTFY_TOKEN=tk_...                       # likewise
```

Appending a duplicate rather than editing is the one way to break this silently: the settings loader
is **last-wins**, so a second, empty `NTFY_BASE_URL=` further down the file beats the value you just
set. `get_transport()` then returns `None`, the dispatch job is never registered, tasks queue as
`pending` forever, and the only trace is one startup log line — `NTFY_BASE_URL is not set;
notifications will queue but never send`. Confirm with `grep NTFY .env` that each key appears exactly
once, with a value.

Then **restart the API** — the dispatch job is registered at startup, so a running process will not
pick up a newly configured transport. `scheduler started: ... dispatch every 1m` in the log is the
confirmation; `dispatch disabled (no transport)` means the transport did not resolve.

Push is **off by default** for every user, and a missing preferences row reads as off. Turn it on,
then register a device:

```bash
curl -s -X PATCH localhost:8000/v1/notifications/prefs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"push_enabled":true}'

curl -s -X POST localhost:8000/v1/notifications/targets \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"label":"pixel"}'
# -> {"id":"...","transport":"ntfy","label":"pixel","target":"<43-character topic>", ...}
```

The topic is minted server-side, never supplied by the client — a client-chosen topic is a
guessable topic. Grant the phone read access to exactly that one topic, and nothing else:

```bash
docker compose exec ntfy ntfy user add phone           # prompts for a password
docker compose exec ntfy ntfy access phone <topic> ro
```

Then in the ntfy Android app: **Settings → Manage users → Add user** for `http://<server>:8080`
with those credentials, then subscribe to the topic. Smoke-test the path without waiting for an
episode:

```bash
curl -s -X POST localhost:8080 -H "Authorization: Bearer $NTFY_TOKEN" \
  -d '{"topic":"<topic>","title":"ShowTrack","message":"test","tags":["tv"]}'
```

##### The topic is a secret

Treat the topic exactly like a password. It is **shown once**, in the response to
`POST /v1/notifications/targets`, and `GET /v1/notifications/targets` will never show it again —
that omission is deliberate and there is a test pinning it, because a leaked read-only access
token must not become a compromised notification stream.

Anyone holding the topic can **read every notification on it and post arbitrary ones to that
phone**, subject only to what the ntfy server's ACLs allow — which is why the phone user above is
scoped `ro` to a single topic rather than given blanket access. There is no way to detect that a
topic has leaked: nothing observes subscribers. **Rotation means deleting the target and
registering a new one** (`DELETE /v1/notifications/targets/{id}`), then re-subscribing the phone.

##### Push requires the VPN

ntfy runs on the home server, so **the phone must be able to reach it** — in practice, be on the
VPN. Off the VPN, notifications queue on the server and the phone sees nothing until it can
connect again. This is the accepted, known cost of self-hosting rather than using Firebase, and it
is the one real regression against FCM.

Once the phone is reaching ntfy over the VPN rather than over `localhost`, set **`NTFY_PUBLIC_URL`**
(uncomment the placeholder `.env.example` already carries, or export it — Compose reads `.env` for
interpolation) to the address **the phone**
uses, e.g. your Tailscale machine name plus `:8080`, and recreate the service:

```bash
docker compose up -d --force-recreate ntfy
```

This is ntfy's own idea of its public address, and a different question from `NTFY_BASE_URL`, which is
where **the backend** reaches ntfy — on one host both are `http://localhost:8080`, but a backend
running inside compose reaches it at `http://ntfy` while the phone never can. ntfy stamps
`NTFY_PUBLIC_URL` into click actions and attachment links, so left at the `localhost` default a phone
following one is sent to *its own* localhost. Nothing today generates such a link, so this affects
link targets only and **never delivery** — it matters from the Android client onward.

##### Why not Firebase

The original design named the Firebase Admin SDK; Phase 6 replaced it (decision 6-A). FCM would
have delivered to a locked, Doze-mode phone anywhere in the world with no VPN, which ntfy cannot —
but it does so by routing every notification about what you are watching through Google, requires
a Google Cloud project and a service-account key to exist at all, and puts a proprietary
dependency on the delivery path of a project whose premise is that it runs on your own hardware.
The transport sits behind a `NotificationTransport` protocol with exactly one method, so nothing
above `send()` knows which one is in use — swapping in FCM or UnifiedPush later is a new file, not
a rewrite.

#### Recommendations

`GET /v1/recommendations` is **content-based, not collaborative filtering**. Nothing about what
anyone else watched or rated feeds a suggestion — a household of one gets recommendations exactly
as good as a household of ten, which is not true of a collaborative recommender.

How a suggestion is produced, in one breath: the **seed job** asks AniList/TMDB what they consider
similar to titles you rated highly, favourited or finished; your own genre profile — weighted by
those same signals — ranks the results it gets back against your taste; anything already in your
library is excluded.

Each item's `reason` names the **single strongest-contributing seed title** and the genres it
shares with the candidate — not every seed that played a part, because "because you liked these
four things a little" is not something you can act on or disagree with. There is deliberately
**no score field**: publishing the blended internal score would let a client sort or display a
number whose scale was never promised, turning a later retune of the ranking weights into a
visible, unexplainable change. The ordering *is* the score.

| Job | Interval | First run after boot | Lock key |
|---|---|---|---|
| Seed | `RECOMMENDATIONS_SEED_HOURS` (default 12) | +5 min | 5000004 |

That boot offset is five minutes where the airing sync's is one, deliberately. Both jobs call
providers through the same memoised client and the same AniList rate limiter, so a shared offset
would not merely co-locate them at boot: with the default 1h and 12h intervals, every later seed
run would land on a sync tick too, forever. The contention is asymmetric — a rate limit costs the
seed job one seed and costs the sync job a whole source for the cycle — so the non-urgent job is
the one that gets moved.

It runs inside the same in-process scheduler and shares the same `SYNC_ENABLED` gate and
multi-replica story as the jobs in [Background sync](#background-sync) above — the advisory lock
is what stops two replicas double-fetching, and `SYNC_ENABLED=false` on an extra replica is the
intent, not just the safety net. Each due run issues one similar-to lookup per seed title; on the
very first sweep it also fetches full details for every candidate that has no `media` row yet,
and TMDB has no batch endpoint for that, so that part is a sequential per-title loop. On a mature
install almost every candidate already has a row, so the cost collapses to near zero after that
first sweep.

Generous by design: unlike the airing dates the sync job tracks, upstream similar-to lists move on
the scale of weeks, not hours. `RECOMMENDATIONS_TTL_HOURS` (default 24) is a backstop, not the
primary invalidation — rating, favouriting, finishing or removing a title invalidates your ranking
immediately, and the TTL exists only for the slow-moving inputs that have no cheap trigger: a
candidate's genres changing on a later sync, or the corpus-wide genre counts drifting as `media`
grows.

The ranking is rebuilt **only on a cursor-less read** — the same request that returns page one.
That is what keeps a pagination cursor stable: a client walking a page it already has cannot
trigger a rebuild underneath itself. Otherwise it pages exactly like `/v1/library` — follow
`next_cursor` until it is `null`. The rebuild takes its own lock too, a per-user
`pg_try_advisory_xact_lock` (5000005) scoped to that one request's transaction rather than the
session-scoped lock the background jobs use — a losing concurrent request just serves the ranking
as it stands rather than duplicating the rebuild.

The feed is deliberately type-agnostic: there is no `?type=` filter and no anime/TV quota, so pool
composition emerges from library composition — AniList seeds return anime, TMDB seeds return TV.
An anime-heavy library will rarely surface a TV recommendation as a result; that is an accepted
cost rather than a bug, and a type filter is purely additive to the contract if it turns out to be
wanted later.

#### Groups

A group is a closed set of people who can see each other's library. There is no follow graph and no
discovery: **membership is the only relationship the system models.**

**An invite code is a credential.** It is 12 characters of Crockford base32 — 60 bits, chosen so
that guessing it is not a strategy, because this server has no rate limiting anywhere and the code
is what stands between an anonymous caller and a working account. It is returned **only to a
member**, on create, join and rotate; `GET /v1/groups` deliberately answers without it, so the
ordinary "what am I in" call never puts a live credential on screen.

| Endpoint | Who | What |
|---|---|---|
| `POST /v1/groups` | any authenticated user | create a group; **the creator is the owner** |
| `GET /v1/groups` | any authenticated user | the groups you are in — `[]` if none, and no invite codes in this shape |
| `POST /v1/groups/join` | any authenticated user | join by code; **idempotent** — a code you already used answers 200 |
| `GET /v1/groups/{id}/members` | member | who is in it, with roles and join dates |
| `POST /v1/groups/{id}/invite/rotate` | **owner only** | issue a new code and a new expiry |
| `DELETE /v1/groups/{id}/members/{user_id}` | yourself, or **owner** for anyone else | leave, or remove |
| `GET /v1/groups/{id}/feed` | member | what members did, newest first — cursor-paginated |
| `GET /v1/groups/{id}/watchlist` | member | the shared list, newest first — cursor-paginated |
| `POST /v1/groups/{id}/watchlist` | member | propose a title; **idempotent** — an already-listed one answers 200 |
| `DELETE /v1/groups/{id}/watchlist/{entry_id}` | member | remove an entry — **any** member may remove **any** entry |
| `GET /v1/groups/{id}/media/{media_id}/reviews` | member | the group's reviews of one title, oldest first |
| `GET /v1/groups/{id}/media/{media_id}/progress` | member | who is ahead on one title |

Every one of those is gated on membership of the group in the path, and a non-member gets the same
`404` whether the group exists or not. Your **own** review is the exception that is not group-scoped
— `POST /v1/reviews`, `PATCH /v1/reviews/{id}` and `DELETE /v1/reviews/{id}` are about a title, not a
group, and the review you write once is visible in every group you are in.

**Codes expire, and rotation is the revocation mechanism.** `GROUP_INVITE_TTL_HOURS` (default 168 —
seven days) bounds how long a code works. There is deliberately **no per-invite tracking**: one code
per group, not one per person invited, so rotating it kills the old code for *everyone* who has it,
including people you meant to keep. That is the trade — per-invite tokens would let you revoke one
person's link, at the cost of an invite-management surface that a household of six does not want.
For the same reason there is **no member cap and no use counter**: expiry plus rotation already
bound what a cap would have protected, and a cap is a setting nobody tunes and a limit nobody
reaches.

**Two roles, `owner` and `member`, and a code only ever produces a `member`.** The only ways to
become an owner are creating the group and inheriting it. A code that could mint an owner would hand
whoever leaked it administrative control of the group rather than merely access to it.

**When the owner leaves, ownership transfers** to the longest-standing remaining member — no
handover step, no ownerless group, and no group that can never be rotated again. If the owner is the
*last* member, the group is deleted instead of being left empty. Both rules live in one place in
`groups/service.py`, so the "I'm leaving" path and the "you're removed" path cannot drift apart.

**A non-member gets `404`, not `403`,** for a group they are not in — including one that does not
exist. `403` would confirm the group is real, which is an existence oracle for anyone willing to
walk UUIDs. The same reasoning is why a wrong code, an unknown code and an expired code all answer
the identical `400 invalid invite code`. A *member* who is not the owner gets `403` on an owner-only
action instead — by then they have already proven the group exists, so there is nothing left to hide.

**Joining a group exposes your whole library, and there is no per-title opt-out.** Every title you
track, your progress on it, your score and your reviews are readable by every member for as long as
you are one. Leaving is the only revocation — there is no "hide this title", no private entry and no
per-group visibility setting. That is the deliberate consequence of the model at the top of this
file: membership *is* the relationship, and a per-title privacy surface would turn a household list
into an access-control system nobody in a household wants to administer. The cost is real, and it is
why an invite code should be handed out with the same care as any other credential.

**The feed is read-fanout, not write-fanout.** No per-group activity rows exist: a read resolves
"activity by members of this group" by joining membership at query time. So joining a group shows
that member's whole history at once, leaving revokes it at once, and neither needs a backfill or a
purge. The cost is paid on every read instead of on every write — the right trade when a group has
six people, and the wrong one if it ever had six thousand.

**What the feed does not contain** matters as much as what it does. Six kinds appear — `added`,
`imported`, `progressed`, `rated`, `completed`, `dropped` — and one PATCH produces exactly one row,
named for the most significant field it changed, with the whole change set in `payload`. Beyond that:

- **An import is one line, not one per title.** An `imported` row has `media_id` **null** and a
  `{"count": N}` payload, so a client must handle a null `media`. The alternative — a row per title —
  would let one 10,000-entry AniList import bury every other member's activity for as far back as
  anyone can scroll, and read-fanout means there is no per-group copy to prune.
- **Removing a title from your library is not an event.** The feed is a log of what happened, not a
  view of current state, so an item can name a title its author no longer tracks. Both title-scoped
  reads answer that case with `200 []` rather than `404` — "nobody in this group tracks it" and "no
  such title" are the same answer to the question being asked, and separating them would hand a
  caller an existence oracle over the whole `media` table. (`POST .../watchlist` *does* `404` on an
  unknown title, because it inserts a client-supplied foreign key and would otherwise surface a
  constraint violation as a 500.)
- **Favouriting and "started watching" do not appear at all.** Both are recorded as deliberate
  omissions rather than gaps, so adding either is a design change to raise, not a hole to fill.

**Progress comparison is raw, and spoilers have exactly one control.** `GET
/v1/groups/{id}/media/{media_id}/progress` reports the episode number each member is on, ordered by
progress descending with a username tiebreak, and clamps nothing — ShowTrack does not reliably know a
title's episode count, and inventing a ceiling would be worse than reporting the number as stored.
The only spoiler affordance anywhere is a review's `contains_spoilers` flag, which the client hides
behind a tap; "episode 24 of a show you are four into" is not hidden from anybody, because knowing
who is ahead is the feature.

**The shared watchlist dies with the group, without a confirmation step.** `group_watchlist.group_id`
is `ON DELETE CASCADE`, and the last member leaving deletes the group — so the last person out takes
the list with them, silently, in the same request that removes their membership. Nothing warns them
first and nothing can be recovered afterwards. A confirmation prompt belongs in the client rather
than the API, but until one exists this is the sharp edge of the leave path.

**Leaving does not take your name off the titles you proposed.** `group_watchlist.proposed_by` is
`ON DELETE SET NULL` against `users`, not against `group_members`, so it is *account deletion* that
clears it — leaving the group does not, and the remaining members keep seeing that id on every title
that person added. What they see is the bare uuid: the list read serves `proposed_by` without
joining `users`, so a leaver's username is not exposed alongside it, and a client that wants to
render a name has to have learned it while they were still a member.

## Never commit credentials

Two layers guard this, and they fail in different ways:

- **`gitleaks`** scans file *content* for credential-shaped strings, in CI across the full history and
  locally via the pre-commit hook. It must be installed to do anything.
- **A filename guard** in the pre-commit hook, and the same check in CI, rejects sensitive *paths* —
  keystores, `.env`, `google-services.json`, service-account JSON. It needs nothing installed, so it
  works on every machine, and it catches the binary keystore no regex would flag.

If a secret does reach a commit, say so immediately. It needs a key rotation and a history rewrite,
and both get worse the longer they wait.

## Project status

| Phase | | |
|---|---|---|
| 0 | Foundations — FastAPI skeleton, structured logging, Alembic, Android skeleton, CI | done |
| 1 | Data models — six tables, six migrations, async test harness | done |
| 1.5 | Repository hygiene — credential guarding, this README | done |
| 2 | Auth — JWT register/login, protected routes | done |
| 3 | Providers — AniList + TMDB integration, unified search, media persistence | done |
| 4 | Library CRUD — add, list, update, remove, cursor-paginated | done |
| 4.5 | AniList import — public profiles, one-way, local wins | done |
| 5 | Sync worker — tiered airing refresh, notification queue | done |
| 6 | Notifications — self-hosted push, dispatcher, preferences | done |
| 7 | Recommendations — content-based over provider similarity, ranked by your genre profile | done |
| 7.5a | Groups — create, invite, join, leave, roles, ownership transfer | done |
| 7.5b | Groups — shared feed, reviews, shared watchlist, progress comparison | done |
| 8–9 | Android foundations and feature modules | |
| 10 | Polish and deployment | |

Architecture documentation lives outside this repository, alongside the working copy: a design doc, a
phased task breakdown, and a decision record. This README is the orientation a fresh clone gets.

## Licence

See [LICENSE](LICENSE).
