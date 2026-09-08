# ShowTrack

A personal anime and TV watch tracker for small closed groups — a household, a couple, a friend
group. You track what you're watching; the people you've invited see your progress, scores and
reviews, and you see theirs.

Deliberately **not** a social network: no follow graph, no strangers, no public profiles. Membership
of a group *is* the relationship, which is what lets the app tell you who's ahead on a show and by
how much.

Self-hosted. It runs on your own machine and is reached over Tailscale.

## What it does

- **Track** anime and TV in one library — status, 1–10 score, episode progress, favourites.
- **Know when the next episode airs.** A background job refreshes airing dates and queues a
  notification before an episode airs; a second job drains that queue to your phone. Push is
  delivered by a self-hosted [ntfy](https://ntfy.sh) server over UnifiedPush — not Firebase.
- **Import** an existing AniList list by username. The profile must be public (the import sends no
  credentials). Read-only and one-way — ShowTrack never writes back.
- **Share with a group** — an activity feed, reviews, a shared watchlist, and side-by-side progress.
  You get in with an invite code, which is also all a new member needs to create an account.
- **Get recommendations** seeded from titles you rated 7+, favourited or finished, ranked by how
  well each candidate's genres match your taste profile. Content-based, not collaborative filtering
  — nothing about what anyone else watched feeds a suggestion.

Data comes from **AniList** (anime, GraphQL) and **TMDB** (TV, REST), normalised behind one provider
interface. `GET /v1/media/search` fans out to both live with a per-provider timeout, so a slow
provider degrades its own share of results rather than failing the search. Every other read path is
database-only.

## Layout

```
show-track/
├── backend/     FastAPI · SQLAlchemy 2.0 (async) · Alembic · PostgreSQL · httpx
├── android/     Kotlin · Jetpack Compose · Hilt · Retrofit · Room
│   └── build-logic/  convention plugins, and the module rules they enforce
├── .github/     backend-ci · android-ci · gitleaks
└── .githooks/   commit-msg · pre-commit
```

One repo, because backend and client share one API contract: a change to an endpoint and the client
that calls it lands as a single reviewable unit.

**Backend** — each domain is four files (`models.py`, `schemas.py`, `service.py`, `routes.py`)
across `users`, `media`, `library`, `sync`, `notifications`, `recommendations`, `groups`. Routes
mount under `/v1`; `/health` stays unversioned because it's an infra probe, not client contract.

**Android** — 16 Gradle modules: `:app`, six `:core:*` (`model`, `designsystem`, `navigation`,
`network`, `database`, `data`) and nine `:feature:*`, one per screen. `:core:model` and
`:core:navigation` are pure Kotlin/JVM with no Android dependency at all.

Three module rules are enforced by the build, not by convention:

1. `:feature:*` modules never depend on each other — cross-feature navigation goes through
   `:core:navigation` route contracts, stitched in `:app`.
2. `:feature:*` modules never depend on `:core:network` or `:core:database`. All data access goes
   through `:core:data`, which is the only module aware of Retrofit and Room. This is what makes
   "Room is a cache, never the source of truth" structural.
3. Nothing downstream of `MediaProvider` imports AniList- or TMDB-specific types.

## Getting started

**Prerequisites:** Docker, `uv`, JDK 21.

### Backend

```bash
cd backend
uv venv --python 3.12          # pinned — CI, the Dockerfile and ruff all say 3.12
uv pip install -r requirements-dev.txt

cp .env.example .env           # REQUIRED before any compose command: docker-compose.yml
                               # interpolates POSTGRES_PASSWORD, SECRET_KEY and REGISTRATION_CODE
                               # from it and refuses to start if any is unset. The shipped
                               # defaults work for development as-is.
docker compose up -d db        # PostgreSQL on :5432

.venv/bin/alembic upgrade head    # REQUIRED before the tests — they run against a real schema
.venv/bin/uvicorn main:app --reload --port 8000
```

`docker-compose.yml` is the **production** configuration; `docker-compose.override.yml` — which
Compose loads automatically when present — is what makes it a development one, adding the
bind-mounted source, the auto-reloader, the dev image target and the published Postgres port. See
[Deployment](#deployment).

`DATABASE_URL` is read by host-run processes only. A container builds its own URL against the `db`
service from `POSTGRES_PASSWORD`, so the two only have to agree on the password.

### Android

```bash
cd android
./gradlew assembleDebug        # or open the folder in Android Studio
```

The API base URL is a Gradle property, defaulting to the emulator's `http://10.0.2.2:8000/`. Point
it at a real server in `~/.gradle/gradle.properties` — **not** the repo's, since a tailnet hostname
is infrastructure detail that must not be committed:

```properties
showtrack.apiBaseUrl=https://<machine>.ts.net/
```

The trailing slash matters to Retrofit.

### Git hooks

```bash
git config core.hooksPath .githooks
```

Conventional-commit message check, ruff/ktlint on staged files, gitleaks secret scan.

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
./gradlew -p build-logic ktlintCheck detekt
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleDebugAndroidTest
```

Four of these are less obvious than they look:

- **`-p build-logic` is separate because it has to be.** `build-logic` is an *included build*; the
  root invocation does not reach it.
- **`testDebugUnitTest` also runs `:build-logic:test`** — the TestKit tests that prove the two
  architecture rules actually fail a build. Without the root lifecycle task they are never executed.
- **`assembleDebugAndroidTest` compiles instrumentation sources** that CI cannot run without an
  emulator, so an androidTest that stops building is caught immediately rather than rotting.
- **`lintDebug` cannot see a hardcoded string in a Compose screen.** `HardcodedText` understands XML
  `android:text` attributes, and this project has no XML layouts. Measured, not assumed. It stays
  for the surfaces it *can* see; a Compose-aware replacement is an open follow-up.

**Memory:** run the Android gate as one bounded invocation with `--max-workers=2`. The Gradle daemon
has been OOM-killed on a 14 GiB machine by running these in parallel.

## Deployment

Self-hosted on a Linux machine you own, reached over Tailscale. The **same `docker-compose.yml`**
runs there as in development — the difference is `docker-compose.override.yml`, which the server
leaves out.

```bash
docker compose -f docker-compose.yml up -d --build
```

**Why production is the base file and development is the override.** The obvious arrangement — a
`docker-compose.prod.yml` selected with `-f` — fails badly in one way: forget the flag on the server
and you silently start production with whatever the base file says. This one used to say
`SECRET_KEY: change-me-use-openssl-rand-hex-32`. Inverted, the worst a forgotten flag does is
bind-mount a source directory.

The other half is `${VAR:?message}` interpolation on the three secrets — Compose **refuses to
start** rather than falling back to a placeholder nobody chose.

### Before the first `up`

```bash
cp .env.example .env
openssl rand -hex 32        # -> SECRET_KEY
openssl rand -hex 32        # -> REGISTRATION_CODE
openssl rand -hex 32        # -> POSTGRES_PASSWORD
```

**Set `POSTGRES_PASSWORD` before the first start, not after.** Postgres reads it only when
initialising an empty data directory; changing it later leaves the old password in the volume and
the api unable to connect.

### The production image

Multi-stage. `build-essential` exists only in a discarded builder stage and pytest/ruff only in the
`dev` target, so the shipped image has neither a compiler nor a test runner, and runs as `appuser`
(uid 1000), never root. A `HEALTHCHECK` probes `/health` with Python — `python:3.12-slim` has no
curl.

Migrations run inline as `alembic upgrade head && exec uvicorn ...` rather than from a one-shot
service, because `depends_on` orders `docker compose up` but **not** the restart policy: after a
reboot the api can start before Postgres accepts connections. Inline, that self-heals — alembic
fails, the container exits, `restart: unless-stopped` brings it back, and it succeeds once the
database is up.

### TLS, and why it is not optional

Neither service publishes beyond `127.0.0.1`. `tailscale serve` runs on the host, terminates TLS
with a real Let's Encrypt certificate for the machine's `*.ts.net` name, and proxies to loopback:

```bash
sudo tailscale serve --bg 8000                  # https://<machine>.ts.net      -> the API
sudo tailscale serve --bg --https=8443 8080     # https://<machine>.ts.net:8443 -> ntfy
sudo tailscale serve status
```

Android blocks cleartext HTTP by default and this app ships no exemption in **either** build type,
so the TLS endpoint is the only way the client can reach the server at all.

### The host must never sleep

APScheduler runs in-process, so a suspended machine silently stops episode sync and notification
dispatch. There is no error to notice — you find out by not being told about an episode.

```bash
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```

### Backups must leave the machine

Not yet built. That box holds every rating and review, none of it regenerable from AniList or TMDB,
and the same disk is not a backup.

## Notifications

Two things are easy to get wrong, and both fail silently.

**Push needs a second app on the phone.** ShowTrack delivers over UnifiedPush, which requires a
distributor app — install ntfy from F-Droid or Google Play. Without one, registration simply reports
that push is unavailable; nothing is broken.

**The phone must be on the VPN.** ntfy runs on your server, so off the tailnet, notifications queue
server-side until the phone can reach it. This is the accepted cost of self-hosting over FCM.

The compose service runs `NTFY_AUTH_DEFAULT_ACCESS=deny-all`, so nobody — including the backend —
can publish until you mint credentials:

```bash
docker compose exec ntfy ntfy user add showtrack
docker compose exec ntfy ntfy access showtrack '*' wo
docker compose exec ntfy ntfy token add showtrack     # prints tk_… — this is NTFY_TOKEN

docker compose exec ntfy ntfy user add phone          # prompts for a password
docker compose exec ntfy ntfy access phone <topic> ro
```

`NTFY_BASE_URL` is where the **backend** reaches ntfy. `NTFY_PUBLIC_URL` is ntfy's own idea of its
public address, stamped into link targets — set it to the address the **phone** uses, which behind
`tailscale serve` is the `https://<machine>.ts.net:8443` URL. Left at localhost, a phone following a
link is sent to its own localhost. Affects link targets only, never delivery.

Exempt both Tailscale and ntfy from battery optimisation on every device. Doze killing the VPN stops
notifications with no error.

## Contributing

Work on a local branch off `dev`, get the gate green, conventional-commit, PR into `dev`,
squash-merge. Release by merging `dev` → `main` and tagging CalVer `vYYYY.0M.MICRO`.

Tests are required for new logic and bugfixes. **Write useful tests, not exhaustive ones** — cover
the meaningful paths and the tricky edge that would actually break. A test must earn its place by
catching a real regression in behaviour we own.

Any API-contract change updates backend and client in the same PR.

A few things that will otherwise cost you an afternoon:

- **Room DAO tests run on the JVM under Robolectric, pinned to `sdk=35`** in
  `src/test/resources/robolectric.properties`. Not 36 — Robolectric ships no shadow jar for it.
- **AGP 9 refuses the Kotlin Gradle Plugin**, which is why ktlint's own source-set hooks never fire
  and the convention plugins widen it by hand. KSP still works, but needs
  `android.disallowKotlinSourceSets=false` in `gradle.properties`.
- **A session's identity map is only invalidated by that session's own ORM writes.** After any
  DB-side mutation, re-read with `populate_existing=True`. Any `SELECT … FOR UPDATE` must carry it
  too, or the lock serialises the transactions and then acts on a stale value. This has been
  rediscovered three times in three disguises.
- **Notifications are never sent from the sync job.** Sync inserts a `NotificationTask`; the
  dispatcher sends. Dedup is a unique constraint, not application logic.
- **Always read a generated Alembic migration against the model before committing it.**
  Autogenerate is a starting point, not a trustworthy output.

## Never commit credentials

Two layers, failing in different ways:

- **`gitleaks`** scans file *content*, in CI across full history and locally via the pre-commit hook.
  It must be installed to do anything.
- **A filename guard** in the hook and in CI rejects sensitive *paths* — keystores, `.env`,
  `google-services.json`, service-account JSON. It needs nothing installed, and it catches the
  binary keystore no regex would flag.

If a secret does reach a commit, say so immediately. It needs a rotation and a history rewrite, and
both get worse the longer they wait.

## Project status

| Phase | | |
|---|---|---|
| 0–7.5b | Backend — foundations, auth, providers, library, AniList import, sync, notifications, recommendations, groups | done |
| 8 | Android foundations — 16 modules, build-enforced rules, design system, HTTP + token store, Room cache, navigation, Hilt | done |
| 8.9 | Push over UnifiedPush | code complete, unverified on device |
| 9a–9c | Nine feature screens, end to end | code complete, unverified on device |
| 9.5 | Visual redesign — palette, nav icons, auth, discover, feed, profile | done |
| 10 | Polish and deployment | in progress |

**Nothing has been run on a physical device by this repository's own tooling** — there is no device
or emulator in the environment it was built in. Screens have been rendered headlessly and the gate
is green, but "code complete" above means exactly that and not more.

Open before this is finished: release signing (there is no `signingConfig`, so `assembleRelease`
produces an unsigned APK Android will refuse to install), off-machine backups, and a manual pass on
a real phone.

Architecture documentation lives outside this repository alongside the working copy — a design doc,
a phased task breakdown and a decision record. This README is the orientation a fresh clone gets.

> This file was condensed from a 2515-line version. Everything cut — 41 numbered device
> walkthroughs, a full `curl` tour of the API, and the long-form rationale behind most decisions —
> is recoverable with `git show ad71028:README.md`.

## Licence

See [LICENSE](LICENSE).
