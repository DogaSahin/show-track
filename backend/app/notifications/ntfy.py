import logging
from typing import ClassVar

import httpx

from app.config import get_settings
from app.http import get_http_client
from app.notifications.transport import (
    NotificationTransport,
    PushMessage,
    TransportPermanent,
    TransportRetryable,
)

logger = logging.getLogger(__name__)

# 429 is the one 4xx that will succeed later; classifying it with the rest would delete a target
# row over a temporary condition.
RETRYABLE_STATUSES = frozenset({429})


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
            response = await client.post(self._base_url, json=payload, headers=self._headers())
        except httpx.HTTPError as exc:
            # Fail open, deliberately: an unclassified failure is treated as retryable, the same
            # direction _parse_retry_after takes. A bug that loses notifications forever is worse
            # than one that retries a few times and gives up.
            #
            # type(exc).__name__, never str(exc): httpx embeds the request URL in its messages.
            raise TransportRetryable(f"ntfy request failed: {type(exc).__name__}") from exc

        if response.is_success:
            return
        if response.status_code >= 500 or response.status_code in RETRYABLE_STATUSES:
            raise TransportRetryable(f"ntfy returned {response.status_code}")
        # Status only. NOT response.text — ntfy echoes the request back on some errors, which
        # would put the topic into the exception message and from there into a log line.
        raise TransportPermanent(f"ntfy rejected the message with {response.status_code}")


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
    return NtfyTransport(base_url=settings.ntfy_base_url, token=settings.ntfy_token)
