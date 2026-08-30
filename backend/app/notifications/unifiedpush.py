import asyncio
import logging
from typing import ClassVar
from urllib.parse import urlparse

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

# The same reasoning as ntfy.PERMANENT_STATUSES, restated rather than imported: only a status
# genuinely ABOUT THIS TARGET may prune it, so 400/401/403 — a wrong token, a bad payload — retry
# instead of deleting every registered device on one misconfiguration. Not shared with ntfy.py,
# because two protocols agreeing today is not the same as one decision: a distributor that starts
# answering 410 for something other than "this endpoint is gone" must be changeable here without
# moving ntfy's policy with it. The cost is that they can drift silently; the comment is the
# mitigation.
PERMANENT_STATUSES = frozenset({404, 410})


class EndpointNotAllowed(Exception):
    """The endpoint a client supplied is not on the configured push server.

    Carries no endpoint value in its message: it is a bearer secret in the same sense the ntfy
    topic is (6-L), and an exception string reaches a log line. app/errors.py turns this into a
    fixed 422 detail for the same reason.
    """


# Every UnifiedPush endpoint ntfy mints is a topic beginning `up` (`https://<server>/upAbC123…`),
# and the README's own setup grants the backend's ntfy user `rw` on exactly `up*`. Pinning the
# path prefix here makes that convention a check rather than a habit.
#
# Why a HOST match alone is not enough, which is the whole reason this constant exists: the host
# is precisely where NTFY_TOKEN is privileged. Without a path check, an authenticated user could
# register `https://<ntfy>/v1/account/token` and the dispatcher would POST to ntfy's ACCOUNT API
# bearing that credential, once per matching episode — or register another user's topic and inject
# notifications into it. The body shape is not attacker-controlled, which caps the severity, but
# "the host is our own" was doing all the work on the one host where that is least sufficient.
UNIFIEDPUSH_PATH_PREFIX = "/up"


def validate_endpoint(endpoint: str) -> None:
    """The origin check (decision A-L), and the reason it is not optional.

    A UnifiedPush endpoint is a callback URL the CLIENT supplies. Storing one unchecked means the
    dispatcher will later POST a body of our choosing — with the ntfy credential attached — to a
    host of the ATTACKER's choosing: a server-side request forgery with a credential leak stapled
    to it, reachable by anyone who can register a device. Pinning scheme, netloc AND path prefix
    to the configured ntfy server is what makes attaching that credential safe at all.

    Netloc, not hostname: `evil.example@ntfy.internal` and a port swap are both host-authority
    tricks that a hostname-only comparison waves through. urlparse puts userinfo and port in
    netloc, so comparing it whole rejects them.

    Path, not just origin: see [UNIFIEDPUSH_PATH_PREFIX]. `/v1/...` — ntfy's own account and admin
    API — is the thing the prefix has to exclude, and it lives on the same host.
    """
    base = get_settings().ntfy_base_url
    if base is None:
        # Not a client error in spirit — the server has no push configuration at all — but it is
        # answered with the same 422 as an off-server endpoint. Deliberate: distinguishing them
        # would tell an unauthenticated-adjacent caller which of the two is true about this
        # deployment, and the client's remedy ("this server cannot register you") is identical.
        raise EndpointNotAllowed("push is not configured on this server")
    try:
        parsed, expected = urlparse(endpoint), urlparse(base)
    except ValueError as exc:
        # urlparse RAISES on some inputs rather than returning a useless parse: an unclosed IPv6
        # literal (`https://[evil/x`) gives "Invalid IPv6 URL", and an NFKC-unsafe netloc gives a
        # netloc-contains-invalid-characters error. Unwrapped, both escape as a 500 for what is
        # plainly a bad request — the same class of bug as an unbounded `target` length, which
        # `TargetCreate` already turns into a 422. Caught here rather than handled globally,
        # because a ValueError from anywhere else in this app is genuinely a 500.
        raise EndpointNotAllowed("endpoint is not a parseable URL") from exc
    if parsed.scheme != expected.scheme or parsed.netloc != expected.netloc:
        raise EndpointNotAllowed("endpoint is not on the configured push server")
    if not parsed.path.startswith(UNIFIEDPUSH_PATH_PREFIX):
        raise EndpointNotAllowed("endpoint is not a UnifiedPush topic on the configured push server")


class UnifiedPushTransport:
    """Data-only delivery: the whole PushMessage as JSON, rendered by the app rather than by ntfy.

    This is the extension point PushMessage's docstring was written for (6-O). NtfyTransport maps
    the message onto ntfy's own title/message/tags fields, so the ntfy client renders it and the
    payload cannot carry anything ntfy has no field for. Here the endpoint is a dumb pipe: the
    body arrives at ShowTrackMessagingReceiver verbatim, which is what lets the notification
    deep-link to `media_id`. Nothing above send() changed to allow it.
    """

    name: ClassVar[str] = "unifiedpush"

    def __init__(self, token: str | None = None, client: httpx.AsyncClient | None = None) -> None:
        # No base URL: each target carries its own absolute endpoint. The configured base URL is
        # still consulted, but through validate_endpoint, which re-reads settings per call.
        self._token = token
        # Injectable for tests only; production passes nothing and shares the process client.
        self._client = client

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"} if self._token else {}

    async def send(self, target: str, message: PushMessage) -> None:
        # RE-VALIDATED at send time, not only at registration. A stored row outlives the config
        # that admitted it: change NTFY_BASE_URL and every previously-registered endpoint now
        # points somewhere we never authorised, and the loop below would ship the ntfy credential
        # there. Retryable rather than permanent on purpose — a typo'd base URL is a SENDER-side
        # fault, and pruning on it would wipe every registered device, which is the exact failure
        # PERMANENT_STATUSES is drawn narrowly to avoid.
        try:
            validate_endpoint(target)
        except EndpointNotAllowed as exc:
            raise TransportRetryable("endpoint is no longer on the configured push server") from exc

        payload = message.model_dump(mode="json")
        try:
            # asyncio.timeout for the reason NtfyTransport documents at length: httpx's read
            # timeout is per READ OPERATION, so a trickling upstream resets it forever and the
            # request never returns — which would hang the dispatcher while it holds the DISPATCH
            # advisory lock. follow_redirects=False for the other reason it gives: httpx turns a
            # redirected POST into a GET and drops the body, and a 200 to that empty GET would
            # mark the task SENT with nothing delivered.
            async with asyncio.timeout(TOTAL_TIMEOUT_SECONDS):
                response = await (self._client or get_http_client()).post(
                    target, json=payload, headers=self._headers(), follow_redirects=False
                )
        except TimeoutError as exc:
            # Its OWN clause, ordered first: asyncio.timeout raises the BUILTIN TimeoutError,
            # which is not an httpx.HTTPError, so the clause below would never catch it and it
            # would escape send() and abort the whole send loop mid-batch.
            raise TransportRetryable(f"unifiedpush request exceeded {TOTAL_TIMEOUT_SECONDS}s") from exc
        except httpx.HTTPError as exc:
            # type(exc).__name__, never str(exc): httpx embeds the request URL in its messages,
            # and the request URL here IS the endpoint secret.
            raise TransportRetryable(f"unifiedpush request failed: {type(exc).__name__}") from exc

        if response.is_success:
            return
        if response.status_code in PERMANENT_STATUSES:
            # Status only. Never response.text — the distributor may echo the request back, which
            # would put the endpoint into an exception message and from there into a log line.
            raise TransportPermanent(f"unifiedpush endpoint rejected the message with {response.status_code}")
        raise TransportRetryable(f"unifiedpush endpoint returned {response.status_code}")


def get_transport() -> NotificationTransport | None:
    """None when push is not configured, mirroring ntfy.get_transport (6-K).

    Gated on the SAME setting as ntfy's: the distributor in this deployment is the ntfy server, so
    a UnifiedPush endpoint that passes validate_endpoint is by construction an ntfy URL. Two
    transports, one piece of configuration — which is also why the registry is never half-built in
    production, and why "a target whose transport has no implementation" is a defensive branch
    rather than an operational state.
    """
    settings = get_settings()
    if not settings.ntfy_base_url:
        logger.info("NTFY_BASE_URL is not set; unifiedpush targets will queue but never send")
        return None
    if not settings.ntfy_base_url.startswith(("http://", "https://")):
        # A scheme-less base URL makes validate_endpoint compare against an empty netloc, so every
        # endpoint would be rejected at send time and every task would burn its attempts to
        # FAILED. Disabling cleanly here is the same posture ntfy.get_transport takes.
        logger.warning("NTFY_BASE_URL is set but missing a scheme (http:// or https://); unifiedpush is disabled")
        return None
    return UnifiedPushTransport(token=settings.ntfy_token)
