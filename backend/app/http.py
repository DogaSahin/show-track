import httpx

# Module constants, not settings: these are code-level policy, and promoting them to config
# would add an .env surface to document for something nobody will tune.
CONNECT_TIMEOUT_SECONDS = 3.0
READ_TIMEOUT_SECONDS = 5.0
# Wall-clock ceiling on a single request, and the reason it sits ABOVE READ_TIMEOUT_SECONDS
# rather than replacing it: httpx's read timeout is per READ OPERATION, not a total. An upstream
# that trickles a byte every four seconds resets it forever, so the request never returns and no
# httpx timeout ever fires. follow_redirects=True can compound the same effect across up to 20
# hops. The client cannot enforce this itself — every caller must wrap its request in
# asyncio.timeout(TOTAL_TIMEOUT_SECONDS); ProviderHTTPClient.request and NtfyTransport.send both
# do, and a future caller that forgets inherits the hang.
TOTAL_TIMEOUT_SECONDS = 8.0

_client: httpx.AsyncClient | None = None


def get_http_client() -> httpx.AsyncClient:
    """One client per process, built on first use.

    Not at import time, for the same reason app/db.py builds its engine lazily: the client
    binds to the running event loop. Not per request either — a fresh client discards
    connection pooling and TLS session reuse.

    Lives here rather than in app/media/providers/ because it has two unrelated consumers now:
    the provider clients and the notification transport. A primitive that lives inside its first
    caller is a primitive the second caller copies — the same reasoning that extracted
    app/sync/locks.py in Phase 5.
    """
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(READ_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS),
            follow_redirects=True,
        )
    return _client


async def close_http_client() -> None:
    """Release the connection pool and clear the memo, so a later get_http_client() rebuilds."""
    global _client
    if _client is not None:
        await _client.aclose()
    _client = None
