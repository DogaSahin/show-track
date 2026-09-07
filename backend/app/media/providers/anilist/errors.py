from typing import Any

from app.media.providers.errors import ProviderUnavailable

# AniList's GraphQL error objects carry a per-error `status` integer. VERIFIED against the live
# API: a missing username answers HTTP 404 with
# {"errors": [{"message": "User not found", "status": 404}], "data": {"MediaListCollection": null}}.
#
# Matching that integer rather than the prose of `message` is deliberate — a substring match on
# "not found" reclassifies ANY unrelated error containing the phrase (a schema error, say) into
# a 404 about the username, which is the failure
# test_an_unrelated_graphql_error_still_raises_provider_unavailable exists to prevent.
_MISSING_USER_STATUS = 404


class AniListGraphQLError(ProviderUnavailable):
    """A response whose GraphQL body carried an `errors` array.

    Subclasses ProviderUnavailable so existing callers, which catch that, are unaffected. It
    carries the parsed payload because fetch_user_list has to re-read it: a private or missing
    profile is a 404 about the request, not a 502 about the upstream.

    SCOPE, stated honestly: the missing-user path is verified and does NOT need this class —
    AniList answers HTTP 404, which the shared transport already surfaces as `None` from `_post`.
    This exists for the case that could not be verified without a known private account: a
    private profile answering 200-with-errors. It is ~20 lines and keyed on a field the live API
    was confirmed to send, so it is cheap insurance rather than speculation — but if a real
    private profile turns out to answer 404 too, delete this module and the `except` in
    _fetch_list_chunk with it.

    Lives in the AniList package rather than the shared providers/errors.py: only AniList speaks
    GraphQL. Architecture rule 3 forbids code DOWNSTREAM of the provider boundary knowing AniList
    types; the AniList package knowing its own is the point of having one.
    """

    def __init__(self, message: str, errors: Any) -> None:
        super().__init__(message)
        self.errors = errors

    def mentions_missing_user(self) -> bool:
        if not isinstance(self.errors, list):
            return False
        return any(isinstance(error, dict) and error.get("status") == _MISSING_USER_STATUS for error in self.errors)
