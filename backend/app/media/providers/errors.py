class ProviderError(Exception):
    """Base for every failure reaching us from an external media provider.

    A 404 is deliberately NOT in this hierarchy: `MediaProvider.get_by_id` returns None for a
    title that does not exist, because "no such title" is an ordinary answer and making it
    exceptional forces every caller into a try/except for the normal case.
    """


class ProviderTimeout(ProviderError):
    """The provider did not answer inside the configured budget."""


class ProviderRateLimited(ProviderError):
    """The provider refused the request because we are over its rate limit.

    `retry_after` is seconds, taken from the response's Retry-After header where the provider
    sends one and computed from a known reset time otherwise. It is None when neither is
    available — the caller must treat that as "unknown", not "retry immediately".
    """

    def __init__(self, message: str = "provider rate limit exceeded", retry_after: float | None = None) -> None:
        super().__init__(message)
        self.retry_after = retry_after


class ProviderUnavailable(ProviderError):
    """A 5xx, a transport failure, or a GraphQL error body — the provider is not answering usefully."""


class UserListNotAvailable(ProviderError):
    """The named profile has no readable list — it does not exist, or it is private.

    A deliberate divergence from this module's convention that 404 stays out of the hierarchy.
    That convention works for `get_by_id`, where None is distinguishable from a successful
    answer. It does not work here: an empty tuple is indistinguishable from a public user with
    an empty list, so the caller would silently report a successful zero-title import.

    Vendor-neutral on purpose — it is the outcome `library` catches, so it must not name AniList.
    """
