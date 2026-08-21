import asyncio
import logging
from typing import ClassVar

import httpx

from app.config import get_settings
from app.http import TOTAL_TIMEOUT_SECONDS, get_http_client
from app.notifications.transport import (
    NotificationTransport,
    PushMessage,
    TransportPermanent,
    TransportRetryable,
)

logger = logging.getLogger(__name__)

# Only a status that is genuinely ABOUT THIS TARGET may prune it. 400/401/403 are sender-side
# facts — a wrong NTFY_TOKEN, a malformed payload, a bad base URL — and pruning on those would
# let one misconfiguration wipe every registered device. Everything outside this set retries and
# is bounded by MAX_ATTEMPTS, so the cost of guessing wrong here is a few retries, not data loss.
# Narrower than "every 4xx" on purpose: the design doc scopes permanence to 404/410 explicitly.
PERMANENT_STATUSES = frozenset({404, 410})


class NtfyTransport:
    """The only file in the project that knows ntfy's wire format.

    Publishes with the JSON form (POST to the base URL with `topic` in the body) rather than
    POST /{topic}, so the topic never appears in a URL — and therefore never in an httpx
    exception message, a proxy access log, or a traceback. The topic is a bearer secret (6-L),
    and keeping it out of URLs is the cheapest way to keep it out of everything that logs URLs.
    """

    name: ClassVar[str] = "ntfy"

    def __init__(self, base_url: str, token: str | None = None, client: httpx.AsyncClient | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        # Injectable for tests only; production passes nothing and shares the process client.
        self._client = client

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"} if self._token else {}

    async def send(self, target: str, message: PushMessage) -> None:
        client = self._client or get_http_client()
        payload = {
            "topic": target,
            "title": message.title,
            "message": message.body,
            # Lets the ntfy client group and filter without parsing the body.
            "tags": ["tv"],
        }
        try:
            # The wall-clock ceiling, and it is not redundant with the client's timeout: httpx's
            # READ_TIMEOUT_SECONDS is per READ OPERATION, not a total, so an upstream trickling a
            # byte every four seconds resets it forever and the request never returns. That is
            # exactly why TOTAL_TIMEOUT_SECONDS sits above READ_TIMEOUT_SECONDS in app/http.py.
            # Without it a slow ntfy hangs send(), which hangs run_dispatch, which holds the
            # DISPATCH advisory lock forever — every subsequent minute logs an APScheduler
            # "maximum number of running instances reached" warning while notifications stop
            # dead. ProviderHTTPClient.request wraps its call the same way for the same reason;
            # the transport is not covered by that one.
            #
            # follow_redirects=False overrides the shared client's default. httpx turns a
            # redirected POST into a GET and drops the JSON body; ntfy would then answer 200 to
            # an empty GET, is_success would be True, and the dispatcher would mark the task SENT
            # with no push ever delivered. Keeping the redirect as a 3xx response here lets it
            # fall into the retryable branch below instead of a silent, undetectable loss.
            async with asyncio.timeout(TOTAL_TIMEOUT_SECONDS):
                response = await client.post(
                    self._base_url, json=payload, headers=self._headers(), follow_redirects=False
                )
        except TimeoutError as exc:
            # Its OWN clause, ordered first: asyncio.timeout raises TimeoutError, which is a
            # builtin and NOT an httpx.HTTPError, so the clause below would never catch it and it
            # would escape send() uncaught — aborting the dispatcher's whole send loop after some
            # tasks already had `attempts` incremented. Retryable, like every other transport
            # fault here: a slow server is the definition of a transient one.
            #
            # Note httpx.TimeoutException is unrelated to this class and is covered below, since
            # it DOES subclass httpx.HTTPError.
            raise TransportRetryable(f"ntfy request exceeded {TOTAL_TIMEOUT_SECONDS}s") from exc
        except httpx.HTTPError as exc:
            # Fail open, deliberately: an unclassified failure is treated as retryable, the same
            # direction _parse_retry_after takes. A bug that loses notifications forever is worse
            # than one that retries a few times and gives up.
            #
            # type(exc).__name__, never str(exc): httpx embeds the request URL in its messages.
            raise TransportRetryable(f"ntfy request failed: {type(exc).__name__}") from exc

        if response.is_success:
            return
        if response.status_code in PERMANENT_STATUSES:
            # Status only. NOT response.text — ntfy echoes the request back on some errors, which
            # would put the topic into the exception message and from there into a log line.
            raise TransportPermanent(f"ntfy rejected the message with {response.status_code}")
        # Everything else — 3xx, the rest of 4xx, 5xx — retries. Per the design doc, only 404/410
        # are permanent; a 503 is not, and neither is a 401 from a bad token or a 3xx from a
        # misconfigured base URL. Bounded by MAX_ATTEMPTS elsewhere, so guessing "retryable" here
        # costs a few attempts rather than deleting a device's registration.
        raise TransportRetryable(f"ntfy returned {response.status_code}")


def get_transport() -> NotificationTransport | None:
    """None when ntfy is not configured (6-K).

    Optional, never required — config.py already records that Phase 2's two required settings
    broke backend-ci on every subsequent PR, and task 6.5's acceptance criterion is that someone
    without push infrastructure can still run the full suite. Absent config means the dispatcher
    is never scheduled and tasks accumulate as `pending`.
    """
    settings = get_settings()
    if not settings.ntfy_base_url:
        logger.info("NTFY_BASE_URL is not set; notifications will queue but never send")
        return None
    if not settings.ntfy_base_url.startswith(("http://", "https://")):
        # A scheme-less NTFY_BASE_URL (e.g. "ntfy.example.com") makes httpx raise
        # httpx.InvalidURL, which does NOT subclass httpx.HTTPError — send() would let it escape
        # uncaught, aborting the dispatcher's send loop after some tasks already had `attempts`
        # incremented. Disabling cleanly here, the same posture as "absent means no transport",
        # keeps that failure mode out of send() entirely rather than papering over it there.
        logger.warning("NTFY_BASE_URL is set but missing a scheme (http:// or https://); ntfy is disabled")
        return None
    return NtfyTransport(base_url=settings.ntfy_base_url, token=settings.ntfy_token)
