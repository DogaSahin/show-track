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
on a title.

The **Android client is a 16-module app you can actually use** — register, search, add a title,
browse and filter your library, and open one to score, progress and favourite it. A design system,
the HTTP stack with encrypted token storage and refresh, a Room cache, the repository layer,
type-safe navigation, Hilt across the whole graph, and push over UnifiedPush sit under four working
screens: **`:feature:auth`** (login, four-field registration, a startup session check),
**`:feature:library`** (status tabs, sorting, cursor paging), **`:feature:detail`** (score, progress,
status and favourite editing, plus Add when a title is not yet tracked) and **`:feature:search`**
(debounced search that adds a result straight into your library, reachable from a search action in
the library screen's header), alongside `:feature:profile`'s push-registration screen from Phase
8.9, which gained a sign-out action in 9a. Four of the nine feature modules —
`:feature:discover`, `:feature:favorites`, `:feature:groups`, `:feature:feed` — are still one-line
placeholders. **Registering needs an invite code** (either your server's `REGISTRATION_CODE` or a
group's invite code — see the **Settings** list under [Backend](#backend) below), and **receiving push needs a UnifiedPush
distributor app** installed separately — see [Push needs a second app
installed](#push-needs-a-second-app-installed--read-this-before-concluding-push-is-broken).

**Nothing in it has been run on a phone or an emulator by this repository's own tooling** — there is
neither in the environment it was built in. That is a real boundary rather than an oversight: the
[Device walkthroughs](#device-walkthroughs) section is a numbered, executable checklist for running
every screen — and every decision this phase made without being able to see one — on your own
device. Everything below distinguishes what is executed from what is merely compiled; see [What is
proven, and what is not](#what-is-proven-and-what-is-not), [Device
walkthroughs](#device-walkthroughs) and [Project status](#project-status).

## What it does

- **Track** anime and TV in one library — status, 1–10 score, episode progress, favourites.
- **Know when the next episode airs.** A background job refreshes airing dates on a schedule and
  queues a notification 24 hours before an episode airs, and again shortly before; a second job
  drains that queue to your phone. Push is delivered by a **self-hosted [ntfy](https://ntfy.sh)
  server**, not Firebase — over **UnifiedPush** to the Android app, or to a plain ntfy topic
  without one. See [Notifications](#notifications).
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

**Android module pattern.** Feature-first and multi-module — **16 Gradle modules**: `:app`, six
`:core:*` (`model`, `designsystem`, `navigation`, `network`, `database`, `data`) and nine
`:feature:*`, one per screen (`auth`, `library`, `detail`, `discover`, `favorites`, `profile`,
`search`, `groups`, `feed`). Two of the core modules — `:core:model` and `:core:navigation` — are
**pure Kotlin/JVM**, with no AGP and no Android dependency at all; the other four are Android
libraries. Five of the nine feature modules carry a real screen: `:feature:profile` (push
registration, Phase 8.9) and — new this phase — `:feature:auth`, `:feature:library`,
`:feature:detail` and `:feature:search`. The remaining four (`discover`, `favorites`, `groups`,
`feed`) still render a single `Text` and exist so the navigation graph, the dependency rules and the
DI wiring are exercised against the shape the finished app will have.
Feature modules never depend on each other, and never on `:core:network` or `:core:database` — all
data access goes through `:core:data`, which is the only module that knows Retrofit and Room exist.
That is what keeps "Room is a cache, never the source of truth" structural rather than a convention
that erodes.

`:app` is the composition root and the only module that sees all of it. `@HiltAndroidApp` on
`ShowTrackApplication` is what makes Hilt build the singleton component, by aggregating every
`@InstallIn(SingletonComponent::class)` module on the app's runtime classpath — `NetworkModule`,
`NetworkConfigModule`, `TokenStoreModule`, `DatabaseModule` and `DataModule` all arrive
transitively, none of them named by hand. An unsatisfied binding anywhere in the app is therefore a
`:app` **compile** error, not a runtime one. `:app` is also where feature modules are pulled
together, which is what makes "features never depend on each other" possible at all, and it is
allowed to depend on `:core:network` — the rule that forbids that constrains `:feature:*` modules
only.

Navigation is that stitching made concrete. `:core:navigation` declares nine `@Serializable` routes
on a `sealed interface AppRoute` — type-safe destinations, so `DetailRoute("abc")` is checked by the
compiler where a `"detail/{mediaId}"` string route is checked by the user's crash report. Each
`:feature:*` module contributes one `NavGraphBuilder.xEntry()` extension that registers its own
destination and names, at most, another feature's *route*; `:app` is the only module that calls all
nine. A feature that needs to reach another screen is handed an `onNavigate: (AppRoute) -> Unit` —
not `(Any) -> Unit`, which would accept the string route back one module up from where it was
removed.

`:app` keeps that wiring as a list rather than as nine calls inline in the `NavHost`, because a list
is inspectable: a JVM test enumerates `AppRoute::class.sealedSubclasses` by reflection and asserts
every declared route has exactly one destination, and that the graph the entry functions actually
build has one node per registration call. (`sealedSubclasses` throws
`KotlinReflectionNotSupportedError` without `kotlin-reflect` on the classpath, and nothing here
pulls it in transitively — so it is a `testImplementation` in `:app`, deliberately test-only: the
route set is enumerated to *check* the graph, never to build it, and the dependency never reaches
`:app`'s runtime classpath.) A route declared and never wired is otherwise a runtime
crash on a screen nobody opened during development. (`NavGraph.addDestination` silently *replaces* a
same-id destination rather than failing, so counting nodes alone would not notice a route registered
twice — hence the comparison against the number of registration calls.)

One route is reachable from outside the app entirely. `:core:navigation` also owns a deep-link
contract — `showtrack://detail/<mediaId>` — which `:feature:detail` registers as a
`navDeepLink<DetailRoute>` and `:app`'s manifest lets in. That is how a push notification's tap
opens the title it is *about* without `:feature:profile`, which builds the notification, ever
naming `:feature:detail`. Same trick as `onNavigate`, one layer out: the route contract travels,
the module does not — and unlike `onNavigate` it survives a cold start, because the tap goes
through the system rather than through a live `NavController`. Three things have to agree for it to
work (the URI the notifier builds, the manifest filter, the graph's registration) and the failure
when they do not is silent — the tap opens the start screen — so all three are pinned by JVM
tests: `PushNotifierTest` on the URI, `NavGraphRegistrationTest` on the graph, and
`MergedManifestTest` on the manifest, which Robolectric can query through a real `PackageManager`
because it loads `:app`'s *merged* manifest — the one `:feature:profile`'s receiver and permission
land in.

The auth gate sits above the `NavHost` and outside it, collecting `AuthEvent` from `:core:data`. A
collector inside a destination would be cancelled exactly when the user navigated away from it,
which is when the request that 401s tends to happen. On `LoggedOut` it navigates to the auth route
with `popUpTo(0) { inclusive = true }`: without clearing the whole back stack, *back* from the login
screen returns to a screen whose every request 401s, and the app looks broken rather than logged
out. `:app` takes that flow from `:core:data`, which re-exposes `:core:network`'s `AuthEventBus` as
one delegating property — the build would permit the composition root to reach past it, but "`:core:data`
is the only module aware of Retrofit and Room" stops being a rule and becomes a habit the moment the
app shell names a network type.

`:core:designsystem` is the theme (colour scheme, typography, shapes) plus the shared component set
every screen styles through instead of one-off Compose styling: `MediaCard`, `CountdownBadge`,
`ScoreChip`, `StatusTab`, `EmptyState`, `LoadingState`, `ErrorState`. `MediaCard` renders cover art
with **Coil 3.2.0** (`coil-compose` + `coil-network-okhttp`, so the OkHttp engine Retrofit already
brings in is shared rather than duplicated by a second HTTP client), and that is why this module —
not `:app` — declares **`android.permission.INTERNET`** in its own manifest. If you are auditing
what the app requests, that is the whole story for that permission: the module that needs it owns
it, and the manifest merger carries it up to any app depending on it. `:core:network` declares the
same permission for the same reason. Coil's singleton loader is configured once in
`ShowTrackApplication`, which hands it the **unauthenticated** OkHttp client — no bearer token ever
travels to a third-party image CDN, and that is a deliberate choice at the composition root rather
than a reliance on the interceptor's host guard.

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
forever. Neither the interceptor nor the authenticator will attach a credential to a request whose
**origin** — scheme, host *and* port — is not the API's, so the authenticated client stays safe to
share with something that fetches images from a third-party CDN — a backstop, not a licence: `:app`
still gives Coil the *unauthenticated* client rather than relying on it. The whole origin and not
just the host, because this is the check that decides whether the Bearer token leaves the device: a
host-only comparison attached it to `http://<api-host>/…` — a downgrade an attacker on the network
can force, putting the token on the wire in clear — and to a different port on the same machine,
which is a different service and a different trust boundary.

Both DataStore files are excluded from Auto Backup and from device transfer, in both `backup_rules.xml`
(API 30 and below) and `data_extraction_rules.xml` (31+, where cloud backup and device transfer are
configured separately). The contents are ciphertext, but the key lives in the Keystore and does not
travel, so a restored copy can never be decrypted — backing it up puts a credential-shaped blob in
the user's cloud and buys nothing. Because a Keystore reset can strand a file that is already on
disk, the store also *self-heals*: ciphertext that will not decrypt is cleared rather than left to
be re-read and re-fail on every launch, which is what "silently logged out forever" actually looks
like. The exclusion path and the DataStore file name live in different modules and different
languages, so a test in `:app` asserts they still match.

The **push registration** file (`showtrack_push`) is excluded by the same two files and for two
separate reasons. Its `endpoint` is a bearer secret in exactly the sense the ntfy topic is — whoever
holds it can post arbitrary notifications to that device — and it is withheld by the list endpoint,
kept out of log lines, and kept out of logcat by `PushRegistrar`, so a cloud backup was the single
route by which it left the phone in plaintext. Separately, and with nothing to do with secrecy, its
`targetId` names a server row belonging to *the device that registered it*: restored onto a second
phone, the next `unregister()` would delete the **old** device's target and silently stop its
notifications. Nothing in the file survives a restore usefully — the distributor mints a fresh
endpoint on first run — so excluding it costs nothing.

`:core:database` is the Room cache the module-dependency rule above exists to protect: one table,
`library_entries`, holding exactly what the library list screen renders — never the full
`:core:model.LibraryEntry` (which nests `Media`) and never a mirror of `LibraryEntryDto`. Room only
ever answers a cold start's first frame; every successful fetch overwrites the table in full through
`LibraryDao.replaceAll`, whose `@Transaction` matters for the same reason the network client's
dispatcher split does — without it, a crash between clearing the table and repopulating it leaves the
next cold start with nothing instead of yesterday's data. `score` stays a raw `String?` in the entity
too, for the same `NUMERIC(3,1)`-precision reason as the DTO; `updated_at` stores as INTEGER epoch
millis (`Converters` maps it to/from `java.time.Instant`) so `LibraryDao.observeAll`'s
`ORDER BY updated_at DESC` is a numeric comparison rather than one over a formatted string. Its DAO
test runs on Robolectric against a real SQLite on the JVM (see [The gate](#the-gate)), so it is
exercised on every `testDebugUnitTest` run rather than sitting compiled-but-unrun for a device that
isn't there. The module depends on nothing else in the monorepo, not even `:core:model` — mapping
between this table and
either the wire or the domain shape is entirely `:core:data`'s job.

`:core:data` is the module that joins those two and the only one a `:feature:*` module may talk to.
It owns the mappers, the pagination and `LibraryRepository` — whose three methods are the entire
data-layer surface a screen ever sees. There is no use-case layer; ViewModels call repositories
directly, because a use case per method would be one class each forwarding a single call. Reading is
cache-then-network: `observeLibrary()` combines the Room flow with the paginator's in-memory list and
takes whichever the paginator has, falling back to the cache while it is empty. So a cold start renders
from the cache immediately, and once the network answers, the paged list takes over *wholesale* — which
means there is no dedup to get wrong and no window where a row appears twice. Only the *first* page is
ever cached: persisting every page would make Room the thing you scroll, which is the source-of-truth
inversion the module rule exists to prevent. Pages 2..n therefore exist nowhere but the paginator,
which is both why the binding is `@Singleton` and why `observeLibrary()` has to read it — returning the
Room flow alone would leave `loadMore()` fetching pages that reach no consumer at all. The combined flow
is `distinctUntilChanged`, because `combine` re-emits on every emission of either source and `refresh()`
moves both.

`refresh()` is a single `CursorPaginator.restart()` rather than a `reset()` followed by a load, and both
halves of that matter. It takes the paginator's lock **once**: with two acquisitions a scroll-triggered
`loadMore()` can land in the gap, fetch page one itself and leave the refresh fetching page two, after
which the refresh caches two pages while believing it cached one — and the repository is a `@Singleton`
with no dispatcher confinement, so a pull-to-refresh overlapping a scroll is the ordinary case rather
than an exotic one. And it **fetches before it mutates**, so a refresh is never destructive: an earlier
version cleared the paginator first, and refreshing a two-page list then emitted the stale one-page
cache for the whole network round trip — measured as
`[1, 2] → [1 (stale)] → [1 (fresh)]` — so the list visibly halved and re-expanded, and a failure left
the user on a stale 20 rows instead of the 40 they had. Fetching first makes it one transition and
makes a failed refresh emit nothing at all. `refresh()` then caches the page that call *returns*
rather than re-reading `paginator.items`, which closes the same race one step further out.

Pagination is hand-rolled rather than Paging 3: Paging's offline
story is `RemoteMediator`, which makes Room the paging source of truth and hands back exactly the rule
the build enforces. `CursorPaginator` carries a `started` flag alongside its cursor, because a null
cursor means both "not begun" and "finished", and conflating them makes an exhausted list silently
repeat page one at the bottom; `PagePaginator` needs no such flag, since `/v1/media/search`'s page
number is never ambiguous. Both take a mutex rather than checking an `isLoading` boolean — a flag is
not atomic across a suspension point, and a scroll listener that fires twice would otherwise send the
same cursor twice.

Mapping is where the `score` contract is honoured or lost. The wire carries `"8.5"` as a JSON *string*
so the value can be reconstructed exactly, and the mappers use `BigDecimal(String)` in and
`toPlainString()` out. Worth knowing precisely which conversions are unsafe, because the obvious guess
is wrong in both directions: Kotlin's `Double.toBigDecimal()` is specified as
`BigDecimal(this.toString())`, so it is safe for the shortest-form decimals this API sends — value-safe
but not scale-safe, since `"8.10"` would come back as `8.1` and `BigDecimal.equals` compares scale —
while the raw `java.math.BigDecimal(double)` constructor turns `8.1` into
`8.0999999999999996447286321199499070644378662109375`. And of the ten
tenths a `NUMERIC(3,1)` score can end in, only `.0` and `.5` are exactly representable as doubles — so
a test written on `8.5`, the value the recorded wire fixture happens to carry, passes with the whole
guarantee deleted. The mapper tests use `8.1`.

Both rules are **enforced by the build**, not by review. Both checks live in one of two "library"
convention plugins — `showtrack.android.library` for Android modules, or the pure-Kotlin/JVM
`showtrack.jvm.library` for `:core:model` and `:core:navigation`, which must stay importable without
pulling in Retrofit, Room or AGP — which every module applies except `:app`, which uses
`showtrack.android.application` and needs neither check: both rules return `null` for it, since it
can never be a `:feature:*` consumer or a `:core:*` producer. Everywhere the checks do apply,
`library`/`jvm.library` carry the identical two of them, so applying `library`/`jvm.library` (plus
`compose`, for the Android modules that use it) by hand cannot opt out. Each inspects its own
project's project-dependencies and fails configuration on a violation, naming the two modules and the
reason. A third check closes the same rule from the export side: a `:core:*` module may not put
`:core:network` or `:core:database` on its `api` configuration, since one `api` edge would re-export
Retrofit or Room to every feature while every declared dependency in the build stayed legal. (For a
`:core:*` module, only the export-side check can ever fire — it can never be a `:feature:*` consumer,
so the first check is unreachable there by construction, not disabled. That is the half of
`showtrack.jvm.library` that stays live: the Kotlin JVM plugin brings java-library's `api`
configuration with it, so `api(project(":core:network"))` from `:core:model` would leak Retrofit
exactly as it would from an Android module.)

**A fourth check, and the reason the third was not enough.** Everything above inspects *declared
project dependencies*, which is a strictly smaller thing than what ends up on a classpath. Measured:
adding `api(libs.retrofit.core)` — a library coordinate, not a project path — to `:core:data`
configured **cleanly**, and put `com.squareup.retrofit2:retrofit` on `:feature:library`'s
`debugCompileClasspath` with no diagnostic anywhere. One character (`implementation` → `api`) and a
feature module could `import retrofit2.*`.

So each `:feature:*` module also runs `verifyDebugArchitecture` / `verifyReleaseArchitecture`, which
resolve that variant's compile classpath and fail if it carries anything owned by `:core:network` or
`:core:database` (`com.squareup.retrofit2`, `com.squareup.okhttp3`, `com.jakewharton.retrofit`,
`androidx.room`). They hang off `preBuild`, so they ride inside `assembleDebug` and
`testDebugUnitTest` rather than needing a gate line of their own, and they read the resolution
*result* — component identities, no artifacts — so nothing upstream has to be built to answer the
question. Only the production classpaths: a feature's own unit tests may legitimately reach for Room.

The resolved classpath is the ground truth, which buys more than the one hole it was written for.
When the probe above was re-run against the check, it named **okhttp as well as retrofit** — nobody
declares okhttp, it arrives underneath Retrofit, and no amount of inspecting declarations could have
seen it. It also settles a question the declaration-side check only guesses at: `apiLeakOf` matches
any configuration name ending in `Api`, including `testApi` and `androidTestApi`, which do not export
to a consumer at all — those simply never appear in a resolved compile classpath.

The rules themselves are pure functions (`build-logic/.../ModuleRules.kt`) with unit tests, and
Gradle TestKit tests drive a real build into each violation to prove the guards are actually reached
— a guard nobody has watched fail is indistinguishable from one that is never invoked. That scaffold
is a *separate* Gradle build, so it needs its own copy of anything the convention plugins depend on:
an SDK location, and `android.disallowKotlinSourceSets=false` (which only became load-bearing when
`showtrack.android.feature` started applying KSP).

Which exposes the weakness in a suite built entirely from `buildAndFail`: **a rule test passes when
the build fails for the wrong reason just as readily as for the right one.** Measured — with the
scaffold's `gradle.properties` removed, so that no feature module can configure at all, all three
dependency-rule tests still pass, because the rule fires from `dependencies.configureEach` and
aborts configuration before AGP ever validates source sets. The suite therefore also carries a
**positive control**: one run that must *succeed*, on a compliant feature module. It is the only
assertion in the class that can tell "the rule fired" from "nothing worked".

Shared build configuration lives in the `build-logic` **included build** rather than `buildSrc`,
which would invalidate every build script on any change to it. It publishes six plugin ids:
`showtrack.android.application`, `.library`, `.compose`, `.feature`, `.hilt`, and
`showtrack.jvm.library`. Test dependencies are declared there, once, instead of per module —
`showtrack.jvm.library` ships its own JVM-appropriate junit/coroutines-test/turbine set for the same
reason.

`showtrack.android.feature` composes `.library`, `.compose` and `.hilt`, and adds the presentation
harness every screen needs: `hilt-navigation-compose` for `hiltViewModel()`, `navigation-compose`
for the `NavGraphBuilder.xEntry()` extension every feature declares, plus lifecycle's Compose
bindings. It deliberately declares **no `project(":core:...")` dependencies**, matching
every other plugin in `build-logic` — an included build that names this repository's module paths
can no longer be applied anywhere else, and the TestKit scaffold (which contains only
`:feature:a`, `:feature:b`, `:core:network` and `:core:data`) fails outright with *"Project with
path ':core:designsystem' could not be found"*. Each feature declares its own module dependencies,
which also keeps them readable in the one file a reviewer looks at.

`showtrack.android.hilt` carries the whole annotation-processing setup — KSP, the Hilt Gradle plugin,
`hilt-android` and the Hilt compiler — for the modules that need a dependency graph. **Thirteen of
the sixteen apply it**: `:app`, `:core:network`, `:core:data` and `:core:database` name it directly,
and all nine features get it through `showtrack.android.feature`, which composes it. Three
constraints on this toolchain are worth knowing before adding a fourteenth. KSP needs
**`android.disallowKotlinSourceSets=false`** in `android/gradle.properties`: AGP 9 owns Kotlin and
otherwise rejects the `kotlin.sourceSets` DSL that KSP registers its generated sources through
(there is no AGP built-in KSP to use instead). And the Hilt Gradle plugin must be **2.60 or newer**
— pinned at **2.60.1** in `gradle/libs.versions.toml` — because 2.57 looks up AGP's `BaseExtension`,
which AGP 9 removed, and fails to apply at all.

The third catches people adding a module by copying another project's build file: **never apply
`org.jetbrains.kotlin.android`.** AGP 9 provides Kotlin itself and hard-fails with *"no longer
required for Kotlin support since AGP 9.0"* — `kotlin { compilerOptions { } }` comes from AGP. The
version catalog still carries the alias, with a comment saying exactly this, so that a pure-JVM or
KMP module could reference it; nothing in this build does.

That last constraint has a sharp edge behind it, and it is Android-only. AGP 9 refuses to run
alongside the Kotlin Android Gradle plugin, and `ktlint-gradle` registers its source-set tasks only
when *that* plugin is applied — so out of the box `ktlintCheck` lints build scripts and not one
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
reason a JVM module's tests run in the same gate command as every other module's. (`:core:model`
and `:core:navigation` carry no test sources of their own today; the alias is what stops the first
one added there from being silently skipped.)

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
```

`GET /v1/library` takes three filter/sort query parameters, all optional:

- **`status`** — one of `watching`, `completed`, `dropped`, `planned`, `paused`. Narrows the page to
  entries in that status; omitted, every status is included. This is what the Android library
  screen's status tabs send.
- **`sort`** — one of `title` (default), `score`, `next_episode_date`. Each has a fixed direction
  (decision 4-J — there is no `order=`), and a paging cursor is bound to the sort it was issued for:
  changing `sort` mid-page answers `400`, not a silently reordered page.
- **`media_id`** — a media (not library-entry) UUID. Answers "is this title in my library?" in one
  request: a page containing that one entry if it is tracked, `{"items":[],"next_cursor":null}` if
  it is not — never a `404`, since "not in your library" is an ordinary, expected answer rather than
  an error. This is what the Android detail screen uses to decide whether to show Add or the
  score/progress/status editor (decision C-C), and it is the phase's only backend change:

```bash
BODY=$(curl -s -H "Authorization: Bearer $TOKEN" localhost:8000/v1/library)
MEDIA=$(echo "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["media"]["id"])')
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8000/v1/library?media_id=$MEDIA"
# -> the one entry for that title. An id belonging to nothing you track answers
#    {"items":[],"next_cursor":null} instead, same as any other empty page.

# only watching titles, soonest-airing first
curl -s -H "Authorization: Bearer $TOKEN" \
  'localhost:8000/v1/library?status=watching&sort=next_episode_date'

# rate it, and mark how far you have got
ENTRY=$(echo "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["items"][0]["id"])')
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
(`"8.5"`) because the column is `NUMERIC(3,1)`: a JSON number is an IEEE 754 double, and a double
cannot hold most one-decimal values exactly — `8.1` becomes
`8.0999999999999996447286321199499070644378662109375`. (`8.5` itself is 17/2 and *is* exact, which is
precisely why it is a misleading value to reason or write tests with; see the `:core:data` notes
above.) `/v1/library` is cursor-paginated — follow `next_cursor` until it is
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

**JDK 21**, not 17 — `gradle/gradle-daemon-jvm.properties` pins the daemon toolchain to 21, every
convention plugin sets `JvmTarget.JVM_21`, and `build-logic` itself compiles at 21. Nothing to
configure if `java -version` already says 21. That properties file also carries per-platform
download URLs, so Gradle can provision a 21 daemon itself rather than failing on an older default
JDK; the gate here has only ever been run on a system JDK that was already 21.

The Android SDK location comes from `local.properties` (`sdk.dir=...`) or from `ANDROID_HOME` /
`ANDROID_SDK_ROOT`, which is what CI sets. The `build-logic` TestKit tests need it too, because they
configure a real Android build in a temp directory.

`android/gradle.properties` carries **`android.disallowKotlinSourceSets=false`**, and it is
load-bearing rather than legacy: KSP (Hilt in thirteen of the sixteen modules, Room in
`:core:database`) registers its
generated sources through the `kotlin.sourceSets` DSL, which AGP 9 rejects by default with *"Using
kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"*. Removing that
line breaks annotation processing across the build. Relatedly: **never add
`org.jetbrains.kotlin.android` to a module** — see the convention-plugin notes above.

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

#### Push needs a second app installed — read this before concluding push is broken

**ShowTrack contains no push transport of its own.** It receives over
[UnifiedPush](https://unifiedpush.org), which means a separate app — a *distributor* — has to be
installed on the device and pointed at your ntfy server. **[ntfy](https://ntfy.sh) is the
distributor this deployment is built around.** Without one installed there is no push at all, and
the interesting part is what that failure looks like: nothing errors, nothing appears in logcat, and
the server has no device to send to.

ShowTrack does not leave you there — Profile detects that no distributor is installed, says so, and
names ntfy — but the ordering matters, so the full sequence is in
[UnifiedPush](#unifiedpush--how-the-showtrack-app-itself-receives) below and is worth following
rather than reconstructing. Two ways to lose a notification silently are in it: no distributor, and
`POST_NOTIFICATIONS` denied on API 33+ (a notification posted without it is dropped with no error
whatsoever).

The phone also has to be able to **reach your ntfy server** — see [Push requires the
VPN](#push-requires-the-vpn). That is the accepted cost of self-hosting instead of using Firebase.

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
./gradlew -p build-logic ktlintCheck detekt
./gradlew testDebugUnitTest       # also runs the build-logic convention-plugin tests
./gradlew assembleDebug assembleDebugAndroidTest
```

**All four commands do more than they look like they do**, and each one looks redundant until you
know why it is there:

- **`-p build-logic` is a separate command because it has to be.** `build-logic` is an *included
  build*, and the root `ktlintCheck detekt` does not reach into one — so until this line existed,
  the module holding both architecture rules, both TestKit suites and all six convention plugins
  was the only module in the repository that nothing linted. Measured: a deliberately malformed
  `build-logic/src/main/kotlin/.../Bad.kt` — double spaces, spaced parameters, 8-space indent, no
  trailing newline — gave **BUILD SUCCESSFUL** under the root command, and under this one gives 15
  ktlint violations and a detekt `NewLineAtEndOfFile`. `android-ci.yml` runs it as its own step for
  the same reason.

- **`ktlintCheck` only sees Kotlin because the Android convention plugins make it.** `ktlint-gradle`
  registers its source-set tasks from `plugins.withId` for the Kotlin Gradle Plugin's ids, and AGP 9
  refuses KGP outright — so on an Android module here, out of the box, `ktlintCheck` lints `.kts`
  build scripts and not one line of source while looking perfectly alive.
  `showtrack.android.library`/`.application` widen it (see above), and a TestKit test drives a
  malformed `.kt` file through a real build so it cannot go quietly inert again. The JVM modules
  need none of that, because a `kotlin.jvm` module *does* apply KGP and ktlint hooks it natively —
  the asymmetry is the plugin's, not a preference. One consequence surprises people: on an Android
  module a `.kt` violation is reported by **`ktlintKotlinScriptCheck`**, because that is the only
  task ktlint registered for the widening to attach to. On a JVM module the same violation comes
  from `ktlintMainSourceSetCheck`. Confusing task name, working check — measured both ways on a
  deliberately malformed file.
- **`testDebugUnitTest` also runs `:build-logic:test`**, wired through a root lifecycle task of that
  name. Those are the Gradle TestKit tests that drive a real build into each architecture-rule
  violation. Without the wiring, the guard on the two dependency rules would have tests that neither
  the documented gate nor CI ever executes — which is the same as not having them.
- **`assembleDebugAndroidTest` compiles tests that cannot be run here**, deliberately. It is the only
  thing that builds the `androidTest` source set at all, so without it an instrumentation test that
  stops compiling rots unnoticed until someone next attaches a device.

`assembleDebugAndroidTest` compiles the instrumentation tests but does not run them — there is no
emulator in this environment or in CI. `:core:network` has one, because the Android Keystore has no
off-device implementation: whether it accepts the app's key spec is only answerable on a device. Run
it by hand against a connected device or emulator with
`./gradlew :core:network:connectedDebugAndroidTest`; the gate compiles it so it cannot rot between
those runs.

The Room tests looked like they would need the same treatment — Room needs a real SQLite, which a JVM
unit test doesn't have — but they run on [Robolectric](https://robolectric.org) instead, which ships a
real native SQLite for the host JVM rather than a fake one. `sdk=35`, not these modules' `compileSdk`
(36): Robolectric selects its Android platform shadow independently of `compileSdk`, and 35 is the
newest level `robolectric:4.15.1` has a shadow for as of this writing — 36 fails immediately with
`API level 36 is not available`. Both `:core:database`'s `LibraryDaoTest` and `:core:data`'s
`LibraryRepositoryImplTest` are therefore genuine JVM unit tests, executed by `testDebugUnitTest`
above, not instrumentation tests — and so are `:app`'s nav-graph, deep-link and merged-manifest
tests and `:feature:profile`'s push tests.

The pin is written **two ways**, and both are in the tree: `:core:database`, `:core:data` and
`:core:network` carry `src/test/resources/robolectric.properties` with `sdk=35`, which is the better
form because a test class added later inherits it instead of rediscovering the failure; `:app` and
`:feature:profile` write `@Config(sdk = [35])` per class. Whichever you copy, pin it — the default
is `targetSdk`, which is 36, which fails.

The repository test builds a real in-memory database rather than a fake DAO, and that is a deliberate
choice: a fake cannot have transaction semantics, so `LibraryDao.replaceAll`'s `@Transaction` would be
unexercisable and the cache path — the whole reason `:core:database` exists — would ship untested end
to end. It also earns its keep immediately. The API sends `updated_at` with microsecond precision and
the cache column is INTEGER epoch millis, so a round trip through SQLite truncates; a fake, or an
expectation written as `dto.toDomain().toEntity().toDomain()`, would assert the mappers against
themselves and never show it. Robolectric needs `testOptions.unitTests.isIncludeAndroidResources =
true`, which is per-module and fails without naming itself when missing.

## What is proven, and what is not

The Android client has never been run on a phone or an emulator, because there is neither in the
environment it was built in. That is a real boundary rather than an oversight, and this section
exists so nobody reads "done" in the table below as "seen working". Three tiers:

**Executed.** The backend suite — **734 tests**, against a real PostgreSQL schema built by the
migrations rather than by `create_all`. The Android JVM suite — **234 tests** under
`./gradlew testDebugUnitTest`: **205** across the app and feature/core modules (up from 117 before
this phase's four new screens and their ViewModels), plus 29 in `build-logic`,
including the Gradle TestKit runs that drive a real build into each architecture-rule violation and
one **positive control** that must succeed, so a rule passing can be told apart from a build that
never configured. The UnifiedPush transport was also driven against a real ntfy in `docker compose`
and the message read back off ntfy's poll endpoint; those exact bytes are the fixture the Android
decoder is tested against, so the two sides are pinned to one recording rather than to each other's
assumptions.

**Compiled but not executed.** `assembleDebugAndroidTest` builds `:core:network`'s
`TokenStoreInstrumentationTest` — the Android Keystore has no off-device implementation, so whether
it accepts the app's key spec is only answerable on a device, and CI can compile that test but not
run it. `ShowTrackMessagingReceiver` is likewise compiled and never run: a `BroadcastReceiver` is
instantiated by the system and cannot be constructed by a JVM test, which is exactly why
`PushRegistrar` and `PushNotifier` were pulled out of it into classes that can be. Its `goAsync()` /
`PendingResult.finish()` lifetime and its `EntryPointAccessors` lookup are untested. **Compose
`@Preview`s render only in Android Studio** — they are a design aid, not a check, and nothing in the
gate or CI executes one.

One dependency skew belongs in this tier rather than the one above. `coil-network-okhttp:3.2.0` is
compiled against **OkHttp 4.12.0**, and Retrofit drags the build onto **5.1.0**, which Gradle then
unifies everything to — so Coil's networking runs on a version it never saw. That was checked
statically: every `okhttp3.*` member the Coil artifact references resolves against 5.1.0, all 22 of
them, with the 4.12.0 run as a control. It has **not** been checked at runtime, because no poster
has ever actually been fetched — that needs a device.

**Not verified at all — for the project owner, on a real device.** Every item below is
device-only; none has been observed:

1. A notification **arrives**, with ntfy installed as the distributor and pointed at the server.
2. **Tapping it opens the right title.** Three things must agree — the URI `PushNotifier` builds,
   `:app`'s manifest intent filter and `:feature:detail`'s `navDeepLink`. All three are individually
   pinned by JVM tests (`PushNotifierTest`, `MergedManifestTest`, `NavGraphRegistrationTest`); the
   end-to-end tap is not.
3. The `POST_NOTIFICATIONS` prompt appears and is honoured. On API 33+ a notification posted without
   it is dropped in silence — the one failure this feature cannot detect for itself.
4. A cold start does **not** add a second push target. The distributor re-delivers the endpoint via
   `onNewEndpoint` on every app start, so this is the server-side idempotency working end to end.
5. `onUnregistered` deletes the row.
6. The "no distributor" → install ntfy → return → **screen updates** path. The ViewModel half is
   executed; that `LifecycleResumeEffect` fires on resume is compiled only.
7. Logging out on the device and logging in as a second account: the second account registers and
   the first stops receiving. This is the half of endpoint takeover that depends on the logout
   `DELETE` landing.

   **This item's gap is closed as of this phase.** `AuthRepositoryImpl.login()` now calls
   `PushRepository.onLoggedIn()` itself, so a second account registers for push at sign-in rather
   than only at the next app start — see `:core:data`'s `AuthRepository`. It is still listed here
   because the *end-to-end* behaviour — the first account's device actually stops receiving — has
   never been observed on hardware; only the call being made has a test behind it.

Nothing in this repository claims any of those seven has happened. [Device
walkthroughs](#device-walkthroughs) below turns each one into a step-by-step check now that the app
has screens to run them from.

## Device walkthroughs

Every walkthrough below was decided by an agent that could not see a screen — either this phase's or
Phase 8's. Each step names what to tap, what should appear, and what it means if it does not: that
last clause is what makes this a test rather than a tour. None of these have been run — there is no
phone or emulator in the environment this was written in — so **this section is unverified
instructions, not a report of what happened.**

**Setup, once:**

1. Start the backend (`docker compose up -d`, `alembic upgrade head`, `uvicorn`) — see
   [Backend](#backend) above — with `REGISTRATION_CODE` set in `.env`.
2. Build and install the debug app on a device or emulator:
   `./gradlew installDebug`, or pointed at a non-default host with
   `-Pshowtrack.apiBaseUrl=http://<host>:8000/` (see [Android](#android) above — the emulator's
   `10.0.2.2` alias needs nothing extra). A physical device needs a reachable host; `10.0.2.2` only
   resolves inside the emulator.
3. Keep the app's data cleared between walkthroughs that call for a "cold start with an empty
   cache" — Android Studio's "Clear data" on the app, or uninstall/reinstall.

### 1. Registering and signing in (`:feature:auth`)

1. Launch the app. **Expect:** the login form, mode toggle showing "Log in" / "Register" — not an
   empty screen and not a crash. *If it crashes here, the startup session check (`AppViewModel`)
   or the `NavHost`'s `Undecided` branch is broken — see [What is proven, and what is
   not](#what-is-proven-and-what-is-not).*
2. Tap **Register**. **Expect:** four fields appear — Username, Email, Password, Invite code — with
   the invite-code field's helper text reading "The code you were given. It may also add you to a
   group." *If the helper text or the field itself is missing, decision C-M's requirement that a
   fresh reader always knows where the code comes from is not being honoured on screen.*
3. Fill in a username, an email, an 8+ character password, and your server's `REGISTRATION_CODE`
   (the same value used in the `curl` walkthrough above — never commit or screenshot the real
   value). Tap **Create account**. **Expect:** you land directly on the library screen (empty,
   since this is a new account). *If instead you see an error naming the invite code, re-check
   `REGISTRATION_CODE` in your `.env` for a stray trailing/duplicate line — see the "last-wins"
   warning in [Notifications](#notifications) for the same failure mode applied to a different
   setting.*
4. Force-stop and relaunch the app. **Expect:** you land back on the library screen without
   re-entering credentials — that is `AuthRepository.hasSession()` deciding `AppStart.Library`
   from the stored token rather than defaulting to the login screen. *If you are asked to log in
   again, the encrypted token store (`:core:network`'s `TokenStore`) did not persist across the
   restart.*
5. To test the login path again, tap **Profile → Sign out**, confirm in the dialog. **Expect:** you
   land back on the login form, and a re-launch of the app does not restore the session — that is
   `AuthRepository.logout()` clearing the token store, then `ProfileViewModel`'s `signedOut` flag
   driving an explicit navigation back to `AuthRoute` (`:app`'s reactive `AuthGate` does **not**
   fire here: it only reacts to a failed token *refresh*, not a user-initiated sign-out with a
   still-valid session — see `ProfileViewModel.signOut`'s KDoc). Log back in with the same
   email/password from the **Log in** tab. **Expect:** success, same as registration. Try a wrong
   password. **Expect:** "That email or password isn't right." *A different message here, or one
   that reveals whether the email exists, is the backend's deliberately generic 401 (decision, see
   `auth_error_invalid_credentials`) being lost somewhere in the client.*

### 2. No empty strip under the login screen

1. On the login screen from walkthrough 1, look at the very bottom of the screen. **Expect:** the
   form's content fills the space; no grey bar, indicator dots or squeezed layout underneath it.
   *A visible empty strip means `NavigationSuiteScaffold`'s `layoutType` is not resolving to
   `NavigationSuiteType.None` while signed out — see the long comment in `MainActivity.kt` next to
   `layoutType =`.*
2. Force-stop and relaunch with a valid stored session (from walkthrough 1 step 4). **Expect:**
   during the brief loading spinner before the library screen appears (`AppStart.Undecided`), the
   same thing holds — no bottom strip, content not squeezed upward. *This is the harder of the two
   cases to catch, because it is on screen for under a second — watch specifically for a flash of
   an empty bar rather than a smooth transition into the library screen with its own three tabs.*

### 3. The status tab row at 360dp (`StatusTabRow`)

1. On the library screen (some entries added — tap the search icon in the header and add a couple
   of titles from there, or repeat the earlier `curl` `POST /v1/library` calls against your
   account), look at the row of tabs under the title: **All**, **Watching**, **Completed**,
   **Dropped**, **Planned**, **Paused**. **Expect:**
   it reads as an ordinary tab bar — one underline indicator under the selected tab, a bottom
   divider under the row, no chip-shaped fill behind any tab. *Two selection indicators on the
   same tab (an underline **and** a filled pill) means the row is still built from `FilterChip`
   children rather than `Tab` — see `StatusTab.kt`'s note on task 9a.8's review of 9a.6.*
2. On a phone-width device (~360dp), confirm **All** is clearly readable and not truncated or
   padded into a wider box than its neighbours. *Uneven tab widths are `ScrollableTabRow`'s minimum
   tab width fighting a short label — the sign the underlying children changed shape again.*
3. Scroll the row left and right with a swipe. **Expect:** it scrolls smoothly with no snapping
   back or clipped label. Tap **Watching**. **Expect:** the list narrows to watching-only entries,
   and if there are none, the empty message reads **"Nothing here"** — see walkthrough 4.

### 4. Empty copy differs by filter

1. From the library screen's **All** tab with at least one entry, tap a status tab that has no
   entries in it yet (e.g. **Dropped**, if you have not dropped anything). **Expect:** the message
   **"Nothing here"** — not "Nothing in your library yet". *The default-tab message under a filter
   is a real regression: it tells a user who has correctly filtered down to zero results that their
   library is empty, which reads as data loss (see `library_empty_filtered` in
   `feature/library/.../strings.xml`).*
2. Remove every entry from your library (or use a brand-new account) and view the **All** tab.
   **Expect:** the message flips to **"Nothing in your library yet"** — the only tab that copy is
   correct on.

### 5. The library's offline story (decision C-B)

1. With at least one entry in your library, put the device into airplane mode (or disable Wi-Fi/
   data) **first**, then force-stop and relaunch the app on the **All** tab. **Expect:** a brief
   loading spinner, then a full-screen error state with a working **Retry** button — on **All**
   too, not only a filtered tab. *This is not a bug to chase: `LibraryViewModel.state` combines
   `error != null` ahead of `loading`/`entries` (`LibraryViewModel.kt:113-119`), and
   `LibraryRepositoryImpl.applyFilter` rethrows on a failed fetch rather than swallowing it, so a
   failed reload always wins over whatever Room holds, on every tab. The Room cache is real, but it
   does not act as an offline fallback in this code path — see the note below for what it actually
   does.*
2. Tap **Retry** while still offline. **Expect:** the same error state reappears rather than a
   crash or a silent no-op.
3. Restore the network and tap **Retry**. **Expect:** the list loads normally on every tab.

Decision C-B's actual user-visible distinction is narrower than "offline shows the cache": a
*filtered* tab is never cached at all (only the default view is, to avoid Room becoming a
queryable mirror of fifteen status/sort combinations — architecture rule 2), so a filtered tab that
gets a genuine, successful, empty response from the server has nothing honest to fall back to and
correctly renders "Nothing here". The default view's cache exists for a narrower case than offline:
`LibraryRepositoryImpl.observeLibrary()` falls back to the cached rows only when a *successful*
fetch returns an empty page (`LibraryRepositoryImpl.kt:97`, `paged.ifEmpty { cached }`) — a
cold-start sentinel and a guard against a legitimately-empty response racing the cache write, not a
network-failure path. There is no reliable device step for this distinction beyond reading the two
files above; it does not show up as a difference in on-screen behaviour between tabs while offline,
because both are showing the same full-screen error at that point.

### 6. Adaptive navigation on a wide layout

1. On a tablet, a foldable opened flat, or an emulator resized to a large window/landscape,
   sign in and look at the three-tab navigation (Home / Favorites / Profile). **Expect:** it
   presents as a **rail** on the leading edge of the screen (icons in a vertical column), not a
   bottom bar. *A bottom bar on a wide layout means the explicit `layoutType` passed to
   `NavigationSuiteScaffold` is not actually reproducing the library's own adaptive default — see
   the comment above `layoutType =` in `MainActivity.kt`, which was verified only by disassembling
   the library's bytecode and never seen rendered.*
2. Resize back down to phone width (or rotate a foldable closed). **Expect:** navigation returns to
   a bottom bar. *If the resize does not trigger a re-layout at all, `currentWindowAdaptiveInfo()`
   is not being recomposed on the size change.*

### 7. Title detail — add, then edit (`:feature:detail`)

1. Open a title's detail screen (via a library row, or the push deep link in walkthrough 10).
   **Expect:** cover image, title, year/genres, and an airing countdown if the title has a next
   episode. For a title not yet in your library: one **"Add to library"** button and nothing else.
2. Tap **Add to library**. **Expect:** the button disables briefly, then the screen switches to the
   score/progress/status/favourite editor with the entry unrated at progress 0. *A screen that
   stays on the Add button after a visible delay, with no error text either, means the request
   completed but the post-add refresh silently failed — see the `detail_add_error` comment in
   `DetailScreen.kt` about `LibraryRepositoryImpl.add`'s two-step add-then-refresh.*
3. Tap the score chip. **Expect:** a dropdown of half-point values from 1.0 to 10.0 plus "Clear
   score". Pick one. **Expect:** the chip updates immediately (optimistic-looking, but confirmed by
   the server's response, not assumed).
4. Use the **−** / **+** buttons to change progress. **Expect:** the number updates and **−** is
   disabled at 0.
5. Tap a different status chip (e.g. **Completed**). **Expect:** it becomes selected; the others
   deselect.
6. Tap the **Favorite** chip. **Expect:** it toggles filled/unfilled.
7. While any one of the above edits is saving (briefly disables the controls), try tapping a second
   control. **Expect:** it does nothing until the first save completes — edits are serialized, not
   concurrent. *A save that lets two edits race is the "second edit is ignored while one is already
   saving" case `DetailViewModelTest` covers on the JVM; this is its on-device counterpart.*

### 8. Search and add-through (`:feature:search`)

From the library screen, tap the search icon in the header (`LibraryHeader`) — that is the only
launch point into `:feature:search`; it is a transient action off the library screen rather than a
fourth bottom-navigation tab.

1. **Expect:** an empty text field and the message "Search for a title
   to add it to your library" — not a blank screen.
2. Type a query (e.g. "frieren"). **Expect:** after a short debounce, a scrollable list of results
   with cover, title and year/genres.
3. Tap a result. **Expect:** a small spinner appears on that row only (not the whole list), then the
   app navigates straight to that title's detail screen, already added. *Tapping does not open a
   preview first — a single tap both adds the title and opens Detail (decision C-N); if nothing
   happens after the spinner, check for `search_add_error`'s inline text on the row.*
4. If your backend has no `TMDB_API_KEY` configured, search a title likely to hit both providers.
   **Expect:** a banner reading "TMDB isn't responding right now — results may be incomplete." above
   the results, and AniList results still shown underneath — the degraded-provider notice.

### 9. Push notifications — Phase 8's seven outstanding checks

Complete [UnifiedPush setup](#unifiedpush--how-the-showtrack-app-itself-receives) above first: a
distributor installed, the server pointed at, and **Profile → Use this app** tapped with
notification permission granted. Each numbered check below corresponds to the same-numbered item in
[What is proven, and what is not](#what-is-proven-and-what-is-not) — runnable for the first time now
that the app has a Profile screen and a Detail screen to land on.

1. **A notification arrives.** Trigger one with the `curl` smoke test in
   [Notifications](#notifications) (`POST localhost:8080 ...`), or wait for a real airing threshold.
   **Expect:** a system notification titled "ShowTrack" (or the title's own name, depending on
   payload) appears within the dispatch interval. *Nothing at all, with no error anywhere, is the
   documented failure mode for a missing distributor or a denied `POST_NOTIFICATIONS` — check both
   before assuming the server side is broken.*
2. **Tapping it opens the right title** — see walkthrough 10 below; it is the same check, done with
   an empty cache from a cold start specifically.
3. **The `POST_NOTIFICATIONS` prompt appears and is honoured.** On first tapping **Use this app** on
   API 33+, confirm the system permission dialog actually appears (not just the in-app copy) and
   that denying it is visibly different from granting it (e.g. `push_permission_grant`'s "Allow
   notifications" prompt stays on screen rather than disappearing).
4. **A cold start does not add a second push target.** Force-stop and relaunch the app several
   times while push is on. **Expect:** `GET /v1/notifications/targets` (with your token) still
   lists exactly one row for this device — not one per launch.
5. **`onUnregistered` deletes the row.** In the distributor app, remove/unregister ShowTrack (or
   uninstall the distributor). **Expect:** the corresponding target disappears from
   `GET /v1/notifications/targets`.
6. **The "no distributor" recovery path.** Uninstall the distributor, open Profile. **Expect:** the
   "Push needs one more app" message naming ntfy. Install ntfy, then return to the app (bring it to
   the foreground, do not relaunch it). **Expect:** the Profile screen updates to the "Turn on
   episode alerts" state on its own, without a restart. *If it stays stuck on "install a
   distributor" after ntfy is installed and you have returned to the foreground, the
   `LifecycleResumeEffect` that re-checks on resume is not firing.*
7. **A real sign-out calls the best-effort logout `DELETE`.** Sign in as account one on the device,
   enable push, confirm a target is registered (`GET /v1/notifications/targets` with account one's
   token). Tap **Profile → Sign out** and confirm. **Expect:** account one's target disappears from
   `GET /v1/notifications/targets` — `AuthRepositoryImpl.logout()` calls `detachPush()` (which
   issues `DELETE /v1/notifications/targets/{id}`) **before** clearing the token store, so this is
   an authenticated call and the DELETE actually reaches the server, unlike the terminal-refresh
   path the rest of this section describes. Log in as a second account on the **same physical
   device** and enable push there too. **Expect:** the endpoint the distributor hands the app is
   the same one as before (it is minted per app **per device**, not per account), so registering it
   under account two takes over the row rather than creating a second one — account two's target
   shows a fresh `created_at` and a cleared label (see the takeover paragraph in
   [UnifiedPush](#unifiedpush--how-the-showtrack-app-itself-receives)). Takeover fires on
   re-registration regardless of whether a clean logout ran first, so this step now exercises both
   paths: the DELETE from a real sign-out, and the takeover a second account's registration causes
   either way.

### 10. The push deep link, end to end

This is the path decision C-C exists for, and the one nothing else in the codebase exercises against
real hardware — three independently-tested pieces (`PushNotifierTest`, `MergedManifestTest`,
`NavGraphRegistrationTest`) have to agree at once.

1. Clear the app's data (a genuinely cold start, empty Room cache) and sign in.
2. Set up push per walkthrough 9, then either wait for a real notification or trigger one with the
   `curl` smoke test, for a title you are **not currently viewing** (background the app or lock the
   device first).
3. Tap the notification from the system tray. **Expect:** the app opens directly to that title's
   detail screen — not the library screen, not a crash, and not the screen the app happened to be
   showing before it was backgrounded.
4. Repeat from a fully killed app (swiped away from recents, not just backgrounded). **Expect:** the
   same result — the deep link survives a cold start because it goes through the system
   (`navDeepLink`), not a live `NavController`.
5. If the detail screen opens but for the **wrong title**, or the app opens to the library screen
   instead: check the notification payload's `media_id` against what `PushNotifier` builds the URI
   from, then the manifest's intent filter, then `:feature:detail`'s `navDeepLink` registration —
   the three things `MergedManifestTest`/`NavGraphRegistrationTest`/`PushNotifierTest` each pin
   individually but that only this walkthrough exercises together.

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

##### UnifiedPush — how the ShowTrack app itself receives

Everything above describes subscribing the **ntfy app** to a topic, which is how push worked
before there was an Android client to receive it. The ShowTrack app uses the other half of the
same server: **UnifiedPush**, with ntfy acting as the *distributor*.

The difference is who mints the target. For `ntfy` the server generates the topic and refuses a
client-supplied one. For `unifiedpush` the distributor on the phone mints a full callback URL and
is the only party that can know it, so the client supplies it — and the backend **origin-checks**
it against `NTFY_BASE_URL` before storing it. That check is not decoration: an unchecked
client-supplied callback URL means the dispatcher will later POST a body of our choosing, with
`NTFY_TOKEN` attached, to a host of the attacker's choosing.

On the phone:

1. Install a UnifiedPush distributor. **ntfy** is the one this deployment is built around — the
   same app you may already have from the topic flow above. If none is installed, ShowTrack's
   Profile screen says so and names ntfy; it does not fail silently.
2. Point the distributor at your server (ntfy: **Settings → Default server**), with a user that
   has write access to `up*` topics.
3. Open ShowTrack → **Profile** → **Use this app**, and allow notifications when Android asks.
   On API 33+ a notification posted without `POST_NOTIFICATIONS` is dropped with no error at all.
   The screen re-reads what is installed on every resume, so installing a distributor and coming
   back is enough — no restart.

The app registers by itself from there — no curl:

```bash
# what the app POSTs, shown for reference. 201 the first time, 200 every time after, same body.
curl -s -X POST localhost:8000/v1/notifications/targets \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"transport":"unifiedpush","target":"http://localhost:8080/upSomeTopic"}'
```

**200 rather than 201 on a repeat is the contract, not a shrug.** The distributor re-delivers the
endpoint through `onNewEndpoint` on *every app start*, so the app cannot avoid re-registering;
without server-side idempotency on the endpoint, one cold start per day would add one push target
per day and a single episode would arrive N times on one phone.

An endpoint already registered to a **different** account is also a 200 — that account's row is
**taken over**, not refused. The reason is the sentence to keep: **possession of the endpoint is
the device credential.** The distributor mints it per app per device and ntfy delivers by topic to
whoever subscribes, so anyone holding the string already receives everything sent to it and a
refusal takes nothing away from them. What a refusal *would* do is strand the next real user of a
shared phone: the app learns it is logged out from a failed token refresh, so the logout `DELETE`
cannot authenticate, the old row survives, and a 409 leaves that device unable to register while
the previous owner's notifications keep arriving on it. Do not "harden" this back into a 409.

One row per endpoint still holds — the global unique constraint is untouched; takeover changes who
owns the row, never how many exist. An endpoint that is not on `NTFY_BASE_URL`, or not a `/up…`
topic on it, is a 422.

A takeover also clears the **label** and refreshes **`created_at`**, because both belong to the
owner rather than to the endpoint: leaving them would show the new user a device name the previous
one typed and a registration date from before they owned the phone — and `GET
/v1/notifications/targets` orders by `created_at`, so the newest device would sort as the oldest.
Re-registering your *own* endpoint changes neither, or every cold start would reset a name you
chose. Two clients racing to register the same endpoint for the first time both get the 200: the
loser of the unique-constraint race re-reads the winner's row instead of surfacing a 500.

What arrives on the wire is the whole notification as JSON, not ntfy's title/message format:

```json
{"title":"Cowboy Bebop","body":"Episode 12 airs soon",
 "media_id":"11111111-2222-3333-4444-555555555555","episode_number":12,"threshold":"24h"}
```

`media_id` is the reason for the whole transport. The app renders the notification itself and its
tap opens `showtrack://detail/<media_id>` — the title the notification is *about*, rather than
whatever screen the app happened to be on. ntfy's own format has nowhere to put that field.

The endpoint is a bearer secret exactly as the topic is: `GET /v1/notifications/targets` withholds
it, and it never appears in a log line. It is also checked by **path**, not just by origin: only
`/up…` is accepted, because the configured ntfy host is exactly where `NTFY_TOKEN` is privileged
and an origin-only check would admit `/v1/account/…` — ntfy's own account API — as a callback the
dispatcher would then POST to bearing that credential.

That path check reads the URL **as `httpx` resolves it**, and rejects any `..` segment in it. Both
halves are needed and neither is decoration. `https://<ntfy>/up/../v1/account/token` has a raw path
that genuinely begins `/up`, so a check on the un-normalized string admits it — and `httpx`
collapses the dot-segment when it builds the request, so the dispatcher POSTs to
`/v1/account/token` with `NTFY_TOKEN` attached. Percent-encoded variants (`/up/%2e%2e/…`,
`/up%2f..%2fv1/…`) go the other way: `httpx` leaves them on the wire for the server to decode, so
only the decoded `..` segment catches them. The general name for the bug is a **parser
differential** — validating a parse of the string that nobody ends up requesting.

**Logging out clears the registration**, best effort on the wire and unconditionally on the
device: the commonest logout is a dead token, so the `DELETE` often cannot land, and keeping the
local record on failure would block the next user behind the app's own "already registered" skip.
The server-side takeover above is what closes the other half.

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
above `send()` knows which *wire format* is in use. Adding UnifiedPush as a second transport was
largely a new file (`app/notifications/unifiedpush.py`) — but not only that, and the difference is
worth recording rather than glossing: `send()` receives only the target **string**, which cannot
distinguish an ntfy topic from a UnifiedPush URL, so the dispatcher had to learn to select a
transport per target **row**. `dispatch_once` and `run_dispatch` went from taking one transport to
taking a `Mapping[PushTransport, NotificationTransport]`. The protocol held; the dispatcher's
signature did not.

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
| 8 | Android foundations — 16 modules, build-enforced dependency rules, design system, HTTP + token store, Room cache, repositories, navigation, Hilt | done |
| 8.9 | Push over UnifiedPush — backend transport, Android receiver, deep-linked taps | in progress |
| 9a | Feature modules, first pass — auth, library, detail and search screens; `media_id` filter on `GET /v1/library` | in progress |
| 9 | Feature modules — five of nine screens now work end to end; discover, favorites, groups and feed remain placeholders | in progress |
| 10 | Polish and deployment | |

**8.9 and 9a are both `in progress` on the code, not verified on the device**, and the distinction is
the point. 8.9's acceptance criterion is "a test push notification is received and tapping it opens
the correct title" — that has never been executed, because there is no device here. 9a's own
acceptance criterion — "follow your own instructions from a clean directory and reach a working
state" — is likewise unmet by this repository's own tooling; [Device
walkthroughs](#device-walkthroughs) is the instructions, not a report that they were followed. The
code and its tests are complete and the gate is green in both cases; the device-level criterion is
what remains open. See [What is proven, and what is not](#what-is-proven-and-what-is-not).

Architecture documentation lives outside this repository, alongside the working copy: a design doc, a
phased task breakdown, and a decision record. This README is the orientation a fresh clone gets.

## Licence

See [LICENSE](LICENSE).
