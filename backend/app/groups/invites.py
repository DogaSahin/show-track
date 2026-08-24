"""Invite-code generation and normalisation.

Pure: no session, no clock, no settings. Kept out of service.py so the one property this
module exists to guarantee — that the write path and the read path normalise identically —
is testable in milliseconds without a database.
"""

import secrets

# Crockford base32: the 32 digits and letters with I, L, O and U removed. Chosen because an
# invite code is read off a screen and typed on a phone, which is exactly the case the
# alphabet was designed for.
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

# 12 x 5 bits = 60 bits. The length is a security parameter, not a style choice: since
# Phase 7.5a an invite code creates an account, and this project has no rate limiting
# anywhere, so the code itself is the only thing standing between a scripted attacker and an
# open registration endpoint. 8 characters (40 bits) becomes guessable the moment somebody
# automates it; 60 does not.
CODE_LENGTH = 12

# Each confusable maps to the character it can only have meant. Safe precisely because
# ALPHABET excludes all three, so there is no code containing a real O to be ambiguous with.
_CONFUSABLES = {"O": "0", "I": "1", "L": "1"}
_SEPARATORS = " -_\t"


def generate_code() -> str:
    """`secrets`, not `random`: this is a credential, and `random` is seeded and predictable."""
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def normalise_code(raw: str) -> str:
    """Fold a human-typed code onto its canonical form.

    ONE function, used by both the storage path and every lookup. Normalising differently on
    write and read would make every code unreachable, and the failure would present as "that
    code is wrong" rather than as a bug — so the two paths share this rather than each doing
    their own uppercase-and-strip.
    """
    stripped = "".join(ch for ch in raw.upper() if ch not in _SEPARATORS)
    return "".join(_CONFUSABLES.get(ch, ch) for ch in stripped)
