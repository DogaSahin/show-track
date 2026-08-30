import json
import uuid
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from sqlalchemy import func, select

from app.media.models import MediaStatus
from app.notifications import service
from app.notifications import unifiedpush as unifiedpush_module
from app.notifications.models import NotificationThreshold, PushTarget, PushTransport, airs_on_for
from app.notifications.transport import PushMessage, TransportPermanent, TransportRetryable
from app.notifications.unifiedpush import (
    EndpointNotAllowed,
    UnifiedPushTransport,
    get_transport,
    validate_endpoint,
)
from tests.conftest import PUSH_ENDPOINT, push_settings
from tests.factories import (
    make_media,
    make_notification_prefs,
    make_notification_task,
    make_push_target,
    make_user,
    make_user_media,
)

NOW = datetime(2026, 8, 21, 12, 0, tzinfo=UTC)
ENDPOINT = PUSH_ENDPOINT

MESSAGE = PushMessage(
    title="Cowboy Bebop",
    body="Episode 12 airs soon",
    media_id=uuid.UUID("11111111-2222-3333-4444-555555555555"),
    episode_number=12,
    threshold=NotificationThreshold.TWENTY_FOUR_HOURS,
)


# --------------------------------------------------------------------------------------
# The origin check (decision A-L)
# --------------------------------------------------------------------------------------


def test_an_endpoint_on_the_configured_server_is_allowed(configured_push):
    validate_endpoint(ENDPOINT)


def test_the_path_check_does_not_reject_a_real_unifiedpush_topic(configured_push):
    """The positive control for the prefix. ntfy mints `/up<random>`, and the README's setup grants
    the backend's user `rw` on `up*` — so a rule tighter than this would reject every genuine
    endpoint, which is a failure mode that looks exactly like "push is broken".
    """
    validate_endpoint("https://push.example.test/upAbCdEf0123456789")
    validate_endpoint("https://push.example.test/up_another-topic")


@pytest.mark.parametrize(
    ("endpoint", "why"),
    [
        ("https://evil.example/UPtopic", "a different host entirely — the plain SSRF case"),
        ("http://push.example.test/UPtopic", "scheme downgrade: the credential would go in clear"),
        ("https://push.example.test:8443/UPtopic", "a port swap is a different origin"),
        (
            "https://evil.example@push.example.test/UPtopic",
            "userinfo trickery — the part a hostname-only check waves through, since urlparse "
            "puts it in netloc but not in hostname",
        ),
        ("https://push.example.test.evil.example/UPtopic", "a suffix that a prefix check would admit"),
        ("file:///etc/passwd", "not even HTTP"),
        # THE PATH CASES. Same host, same scheme — so an origin-only check admits both, and the
        # host is exactly where NTFY_TOKEN is privileged.
        (
            "https://push.example.test/v1/account/token",
            "ntfy's own ACCOUNT API. Admitted by an origin-only check, and the dispatcher would "
            "then POST to it bearing NTFY_TOKEN, once per matching episode",
        ),
        (
            "https://push.example.test/v1/account/access",
            "ntfy's ACL API, same host, same credential",
        ),
        (
            "https://push.example.test/someone-elses-topic",
            "another user's ntfy topic — not an SSRF, but notification injection into a stream "
            "that is not this device's",
        ),
        ("https://push.example.test/", "no topic at all"),
        # urlparse RAISES on these rather than returning a useless parse. Unwrapped they are a
        # 500 for what is plainly a bad request.
        ("https://[evil/x", "an unclosed IPv6 literal: urlparse raises ValueError('Invalid IPv6 URL')"),
        ("https://push.example\u2100test/upTopic", "an NFKC-unsafe netloc, which urlparse also refuses"),
        # THE PARSER-DIFFERENTIAL CASES, and the reason the path check reads httpx.URL rather than
        # urlparse. Every one of these has a raw path that literally begins `/up`, so the original
        # `urlparse(endpoint).path.startswith("/up")` admitted it — and httpx then resolved it to
        # ntfy's account API, which is where NTFY_TOKEN is privileged. MEASURED against the real
        # dispatcher before the fix: `POST https://<ntfy>/v1/account/token` with
        # `Authorization: Bearer <NTFY_TOKEN>`.
        (
            "https://push.example.test/up/../v1/account/token",
            "a dot-segment httpx collapses when it builds the request: validated as /up/..., sent as /v1/account/token",
        ),
        (
            "https://push.example.test/upx/../../v1/account/token",
            "the same, from a topic-shaped first segment — the prefix check does not even need a real `/up` directory",
        ),
        (
            "https://push.example.test/up/%2e%2e/v1/account/token",
            "percent-encoded dot-segments, which httpx leaves on the wire for the SERVER to "
            "normalize — so httpx.URL.path still begins /up and only the decoded `..` segment "
            "catches it",
        ),
        (
            "https://push.example.test/up%2f..%2fv1/account/token",
            "the %2f variant of the same: the separator itself is encoded",
        ),
        (
            "https://push.example.test/up/..%00/v1/account/token",
            "a NUL-suffixed traversal segment — why the segment test is startswith('..') and not == '..'",
        ),
        (
            "https://push.example.test/%75p/../v1/account/token",
            "the first segment is not even spelled `up` in the raw string; httpx decodes AND "
            "collapses, which is precisely the parse the check now runs on",
        ),
    ],
)
def test_an_endpoint_off_the_configured_server_is_rejected(configured_push, endpoint, why):
    """Every one of these is a URL the DISPATCHER would later POST to with the ntfy credential
    attached. This is the test that stops "it starts with the right hostname" being good enough.
    """
    with pytest.raises(EndpointNotAllowed):
        validate_endpoint(endpoint)


def test_no_endpoint_is_allowed_when_push_is_unconfigured(monkeypatch):
    """Fails CLOSED. With no configured server there is nothing to compare against, so admitting
    the endpoint would mean admitting every endpoint — on the deployment least likely to notice.
    """
    monkeypatch.setattr(unifiedpush_module, "get_settings", lambda: push_settings(ntfy_base_url=None))

    with pytest.raises(EndpointNotAllowed):
        validate_endpoint(ENDPOINT)


# --------------------------------------------------------------------------------------
# The transport
# --------------------------------------------------------------------------------------


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_the_whole_message_is_posted_as_json_to_the_endpoint(configured_push):
    """Data-only delivery (6-O): the app renders the notification, so every structured field has
    to survive the wire. `media_id` in particular — without it the notification cannot deep-link,
    which is the entire reason this transport exists rather than reusing ntfy's title/message.
    """
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["json"] = json.loads(request.content)
        seen["auth"] = request.headers.get("Authorization")
        return httpx.Response(200)

    async with _client(handler) as client:
        await UnifiedPushTransport(token="tok", client=client).send(ENDPOINT, MESSAGE)

    assert seen["url"] == ENDPOINT
    assert seen["json"] == {
        "title": "Cowboy Bebop",
        "body": "Episode 12 airs soon",
        "media_id": "11111111-2222-3333-4444-555555555555",
        "episode_number": 12,
        "threshold": "24h",
    }
    # The credential goes only because the origin check already proved this endpoint is our own
    # server. Ship it to an arbitrary client-supplied host and it is a credential leak.
    assert seen["auth"] == "Bearer tok"


async def test_a_stored_endpoint_that_is_no_longer_on_the_configured_server_is_not_posted_to(monkeypatch):
    """A row outlives the configuration that admitted it. Move NTFY_BASE_URL and every stored
    endpoint now points somewhere never authorised — so the check is re-run at SEND time, not
    only at registration, or the credential goes out anyway.

    Retryable rather than permanent on purpose: a typo'd base URL is a sender-side fault, and
    pruning on it would delete every registered device.
    """
    monkeypatch.setattr(
        unifiedpush_module, "get_settings", lambda: push_settings(ntfy_base_url="https://moved.example")
    )
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200)

    async with _client(handler) as client:
        with pytest.raises(TransportRetryable):
            await UnifiedPushTransport(client=client).send(ENDPOINT, MESSAGE)

    assert called is False, "the request was issued before the origin was re-checked"


@pytest.mark.parametrize("status", [404, 410])
async def test_a_gone_endpoint_is_permanent(configured_push, status):
    async with _client(lambda request: httpx.Response(status)) as client:
        with pytest.raises(TransportPermanent):
            await UnifiedPushTransport(client=client).send(ENDPOINT, MESSAGE)


@pytest.mark.parametrize("status", [401, 403, 429, 500, 503, 307])
async def test_every_other_status_retries_rather_than_pruning_the_target(configured_push, status):
    """The narrowness is the point, and it is ntfy.PERMANENT_STATUSES' reasoning restated: a 401
    from a wrong token is a fact about the SENDER, and pruning on it would let one
    misconfiguration wipe every registered device. 307 is here because httpx would otherwise
    turn a redirected POST into a bodiless GET and a 200 to that would read as delivered.
    """
    async with _client(lambda request: httpx.Response(status)) as client:
        with pytest.raises(TransportRetryable):
            await UnifiedPushTransport(client=client).send(ENDPOINT, MESSAGE)


async def test_a_transport_level_failure_is_retryable_and_names_no_url(configured_push):
    """type(exc).__name__, never str(exc): httpx embeds the request URL in its messages, and here
    the request URL IS the endpoint secret.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("failed to connect to https://push.example.test/UPabcdef0123456789")

    async with _client(handler) as client:
        with pytest.raises(TransportRetryable) as caught:
            await UnifiedPushTransport(client=client).send(ENDPOINT, MESSAGE)

    assert ENDPOINT not in str(caught.value)


def test_the_transport_is_absent_without_push_configuration(monkeypatch):
    """6-K: push stays optional, so the whole suite runs on a machine with no ntfy at all."""
    monkeypatch.setattr(unifiedpush_module, "get_settings", lambda: push_settings(ntfy_base_url=None))

    assert get_transport() is None


def test_the_transport_is_absent_for_a_scheme_less_base_url(monkeypatch):
    """A scheme-less base URL makes urlparse produce an empty netloc, so EVERY endpoint would be
    rejected at send time and every task would burn its five attempts to FAILED. Disabling
    cleanly here keeps that out of send() entirely — the same call ntfy.get_transport makes.
    """
    monkeypatch.setattr(unifiedpush_module, "get_settings", lambda: push_settings(ntfy_base_url="push.example.test"))

    assert get_transport() is None


# --------------------------------------------------------------------------------------
# Idempotent registration (decision A-O)
# --------------------------------------------------------------------------------------


async def test_registering_the_same_endpoint_twice_creates_one_row(db_session, configured_push):
    """`onNewEndpoint` fires on EVERY app start, not once. Without the lookup, one cold start per
    day silently adds a row and one episode then yields N pushes to one phone.
    """
    user = make_user(username="up1", email="up1@example.com")
    db_session.add(user)
    await db_session.flush()

    first, created_first = await service.create_unifiedpush_target(
        db_session, user_id=user.id, endpoint=ENDPOINT, label="Pixel 8"
    )
    second, created_second = await service.create_unifiedpush_target(
        db_session, user_id=user.id, endpoint=ENDPOINT, label="Pixel 8"
    )

    assert (created_first, created_second) == (True, False)
    assert first.id == second.id
    # ARCHITECTURE RULE 8. Asserted through Core, never session.get(): the identity map is only
    # invalidated by this session's OWN ORM writes, so an assertion that reads back through the
    # map can be answered from cache and pass while the database holds two rows. Measured
    # elsewhere in this project — after a DB-side cascade session.get returns the stale row while
    # the Core count reads 0.
    #
    # It carries no weight against any mutation of THIS code, and that is worth stating rather
    # than leaving for the next reader to rediscover: `uq_push_targets_transport_target` is
    # global on (transport, target) (6-D), so any second row with this endpoint is an
    # IntegrityError before a count could ever read 2. The assertion starts earning its keep the
    # day that constraint is narrowed to per-user — which is exactly when someone would delete it
    # for looking redundant.
    count = await db_session.scalar(select(func.count()).select_from(PushTarget).where(PushTarget.user_id == user.id))
    assert count == 1


async def test_a_second_user_takes_the_endpoint_over_rather_than_being_refused(db_session, configured_push):
    """Possession of the endpoint IS the device credential (decision A-O, revised).

    The distributor mints it per app per device and ntfy delivers by topic to whoever subscribes,
    so anyone holding this string already receives everything sent to it — a refusal takes nothing
    away from an attacker. What it does take away is the next real user of a shared phone: the
    logout DELETE cannot authenticate after a terminal refresh failure, so the previous owner's
    row survives and a 409 strands them permanently.
    """
    first = make_user(username="first", email="first@example.com")
    second = make_user(username="second", email="second@example.com")
    db_session.add_all([first, second])
    await db_session.flush()
    original, created = await service.create_unifiedpush_target(
        db_session, user_id=first.id, endpoint=ENDPOINT, label=None
    )
    assert created is True

    taken, created_again = await service.create_unifiedpush_target(
        db_session, user_id=second.id, endpoint=ENDPOINT, label=None
    )

    assert created_again is False
    assert taken.id == original.id
    assert taken.user_id == second.id
    # ARCHITECTURE RULE 8: through Core, never session.get() — the identity map is invalidated
    # only by this session's own ORM writes.
    #
    # And it is STILL hygiene rather than a live guard, which was worth measuring rather than
    # assuming. Takeover looked like the first path where a plausible bug (reassigning by
    # INSERTING instead of updating) would produce a silent duplicate. It does not: that mutation
    # dies on `uq_push_targets_transport_target` as an IntegrityError, and with the constraint
    # dropped it dies on the `taken.id == original.id` assertion two lines up — which is the
    # stronger check anyway, since it pins the SAME row rather than merely one row. Keep this
    # assertion for the day 6-D's constraint is narrowed to per-user; do not claim it is load
    # bearing today.
    count = await db_session.scalar(select(func.count()).select_from(PushTarget))
    assert count == 1


async def test_after_a_takeover_the_previous_owner_stops_receiving(db_session, configured_push):
    """The half a status-code assertion cannot see. A takeover that changed `user_id` but left the
    old owner reachable would be worse than the 409 it replaced — the endpoint would then serve
    both accounts at once, which is the exact privacy leak this whole change exists to close.
    """
    tag = uuid.uuid4().hex[:8]
    previous = make_user(username=f"prev{tag}", email=f"prev{tag}@example.com")
    current = make_user(username=f"curr{tag}", email=f"curr{tag}@example.com")
    airs_at = NOW + timedelta(hours=20)
    media = make_media(external_id=tag, status=MediaStatus.AIRING, next_episode_number=12, next_episode_date=airs_at)
    db_session.add_all([previous, current, media])
    await db_session.flush()
    db_session.add_all(
        [
            make_user_media(previous.id, media.id),
            make_notification_prefs(previous.id, push_enabled=True),
            make_notification_task(
                previous.id,
                media.id,
                episode_number=12,
                threshold=NotificationThreshold.TWENTY_FOUR_HOURS,
                airs_on=airs_on_for(airs_at),
            ),
        ]
    )
    await service.create_unifiedpush_target(db_session, user_id=previous.id, endpoint=ENDPOINT, label=None)
    await db_session.flush()

    await service.create_unifiedpush_target(db_session, user_id=current.id, endpoint=ENDPOINT, label=None)
    up_stub = RecordingTransport("unifiedpush")
    summary = await service.dispatch_once(db_session, {PushTransport.UNIFIEDPUSH: up_stub}, now=NOW)

    assert up_stub.sent == [], "the previous owner's notification still reached the handed-over device"
    # Nowhere to send is SKIPPED, not failed — the previous owner now has no registered target.
    assert (summary.sent, summary.skipped) == (0, 1)


async def test_an_off_server_endpoint_is_refused_before_the_lookup(db_session, configured_push):
    """Order matters: validate, THEN look up. Check after the lookup and an endpoint that was
    legal when stored — but is off-server now — takes the "already registered" path and is
    re-blessed as valid.
    """
    user = make_user(username="up2", email="up2@example.com")
    db_session.add(user)
    await db_session.flush()

    with pytest.raises(EndpointNotAllowed):
        await service.create_unifiedpush_target(
            db_session, user_id=user.id, endpoint="https://evil.example/UPtopic", label=None
        )

    count = await db_session.scalar(select(func.count()).select_from(PushTarget).where(PushTarget.user_id == user.id))
    assert count == 0


# --------------------------------------------------------------------------------------
# Per-target transport selection (decision A-P)
# --------------------------------------------------------------------------------------


class RecordingTransport:
    def __init__(self, name: str) -> None:
        self.name = name
        self.sent: list[str] = []

    async def send(self, target: str, message: PushMessage) -> None:
        self.sent.append(target)


async def _queued_with_targets(db_session, *, transports_and_targets) -> None:
    """One user, one tracked airing title, one pending task, and the given push target rows."""
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    airs_at = NOW + timedelta(hours=20)
    media = make_media(external_id=tag, status=MediaStatus.AIRING, next_episode_number=12, next_episode_date=airs_at)
    db_session.add_all([user, media])
    await db_session.flush()

    db_session.add_all(
        [
            make_user_media(user.id, media.id),
            make_notification_prefs(user.id, push_enabled=True),
            make_notification_task(
                user.id,
                media.id,
                episode_number=12,
                threshold=NotificationThreshold.TWENTY_FOUR_HOURS,
                airs_on=airs_on_for(airs_at),
            ),
        ]
    )
    for transport, target in transports_and_targets:
        db_session.add(make_push_target(user.id, transport=transport, target=target))
    await db_session.flush()


async def test_a_dispatch_routes_each_target_to_its_own_transport(db_session):
    """The decision, stated as a test. `send()` receives only the target string, so a single
    transport with a routing wrapper inside it could not tell these two apart — the transport
    lives on the ROW, and dispatch_once is the only layer that can see it.
    """
    topic = "ntfy-topic-for-routing"
    await _queued_with_targets(
        db_session, transports_and_targets=[(PushTransport.NTFY, topic), (PushTransport.UNIFIEDPUSH, ENDPOINT)]
    )
    ntfy_stub, up_stub = RecordingTransport("ntfy"), RecordingTransport("unifiedpush")

    summary = await service.dispatch_once(
        db_session,
        {PushTransport.NTFY: ntfy_stub, PushTransport.UNIFIEDPUSH: up_stub},
        now=NOW,
    )

    assert summary.sent == 1
    assert ntfy_stub.sent == [topic]
    assert up_stub.sent == [ENDPOINT]


async def test_a_target_with_no_configured_transport_is_skipped_not_raised(db_session):
    """6-F: `skipped` and `failed` are different diagnoses. A target we cannot address is the
    same class of fact as "no device registered" — nowhere to send, not a delivery failure — and
    it must not raise, which would abort the whole batch mid-loop.
    """
    await _queued_with_targets(db_session, transports_and_targets=[(PushTransport.UNIFIEDPUSH, ENDPOINT)])

    summary = await service.dispatch_once(db_session, {}, now=NOW)

    assert (summary.skipped, summary.failed, summary.sent) == (1, 0, 0)


async def test_an_unaddressable_target_does_not_burn_the_attempt_budget(db_session):
    """Filtered out BEFORE the attempt is recorded, not inside the send loop. Inside the loop the
    task would have `attempts` incremented for a target it can never reach, and after five runs
    it would be FAILED — a delivery failure reported for something that was never attempted.
    """
    await _queued_with_targets(
        db_session,
        transports_and_targets=[(PushTransport.NTFY, "reachable-topic"), (PushTransport.UNIFIEDPUSH, ENDPOINT)],
    )
    ntfy_stub = RecordingTransport("ntfy")

    summary = await service.dispatch_once(db_session, {PushTransport.NTFY: ntfy_stub}, now=NOW)

    assert summary.sent == 1
    assert ntfy_stub.sent == ["reachable-topic"], "the unaddressable target must not reach a transport"
