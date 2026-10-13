"""Per-email login throttle, in process.

Keyed on the email, not the client address: the API runs in Docker behind `tailscale serve`, so
uvicorn sees every request from the bridge gateway and a per-address bucket would be one global
bucket. Correct for the single replica the deployment already mandates (the in-process scheduler
pins it to one); a second replica would need shared state.

Pure: no FastAPI, no clock. Callers pass `now` (monotonic seconds).
"""

import math
from collections import OrderedDict

MAX_ATTEMPTS = 5
WINDOW_SECONDS = 15 * 60
# Sized above what a spray can reach, so the refuse branch stays a backstop. Only attempts that
# reach argon2 keep a key (refunded ones drop it), and argon2 runs ARGON2_CONCURRENCY=2 at a time
# at roughly 20 hashes/s each: 2 x 20 x 900 s ~= 36k keys per window. Timestamps are floats in a
# list of at most MAX_ATTEMPTS, so even a full map is a few tens of MB.
MAX_KEYS = 100_000


def key_for(email: str) -> str:
    """The validated email (EmailStr already normalised it), case-folded the way login looks it
    up. Python's lower() and Postgres's may differ on exotic Unicode local parts; the worst case
    is a separate bucket for that spelling.
    """
    return email.strip().lower()


class LoginThrottle:
    def __init__(
        self, *, max_attempts: int = MAX_ATTEMPTS, window_seconds: int = WINDOW_SECONDS, max_keys: int = MAX_KEYS
    ) -> None:
        self._max_attempts = max_attempts
        self._window = window_seconds
        self._max_keys = max_keys
        self._attempts: OrderedDict[str, list[float]] = OrderedDict()

    def begin(self, key: str, now: float) -> int | None:
        """Check AND record in one synchronous step — no await between them — so concurrent
        requests cannot all pass the check before any of them is counted.

        Returns seconds to wait (>= 1) when refused; None when the attempt was recorded.
        """
        attempts = self._live(key, now)
        if attempts is not None and len(attempts) >= self._max_attempts:
            return max(1, math.ceil(attempts[0] + self._window - now))
        if attempts is None:
            if len(self._attempts) >= self._max_keys and not self._make_room(now):
                # Never evict a live key: that would let a spray flush a locked victim's count.
                return self._window
            attempts = self._attempts[key] = []
        attempts.append(now)
        return None

    def refund(self, key: str, at: float) -> None:
        """Forget an attempt that never reached a password check (hashing was busy)."""
        attempts = self._attempts.get(key)
        if attempts is None:
            return
        if at in attempts:
            attempts.remove(at)
        if not attempts:
            del self._attempts[key]

    def reset(self, key: str) -> None:
        self._attempts.pop(key, None)

    def _live(self, key: str, now: float) -> list[float] | None:
        attempts = self._attempts.get(key)
        if attempts is None:
            return None
        cutoff = now - self._window
        attempts[:] = [t for t in attempts if t > cutoff]
        if not attempts:
            del self._attempts[key]
            return None
        return attempts

    def _make_room(self, now: float) -> bool:
        """Evict keys whose every attempt has expired, oldest first. True once there is room."""
        cutoff = now - self._window
        for key in list(self._attempts):
            if self._attempts[key][-1] <= cutoff:
                del self._attempts[key]
                if len(self._attempts) < self._max_keys:
                    return True
        return len(self._attempts) < self._max_keys


login_throttle = LoginThrottle()
