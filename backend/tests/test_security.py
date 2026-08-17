import time
import uuid

from app.users import security


def test_password_hash_round_trips() -> None:
    hashed = security.hash_password("correct horse battery staple")

    assert security.verify_password(hashed, "correct horse battery staple") is True


def test_password_hash_is_not_the_plaintext() -> None:
    hashed = security.hash_password("hunter2")

    assert "hunter2" not in hashed
    # argon2id, not argon2i or argon2d: measured default of argon2-cffi 25.1.0.
    assert hashed.startswith("$argon2id$")


def test_wrong_password_is_rejected() -> None:
    hashed = security.hash_password("right")

    assert security.verify_password(hashed, "wrong") is False


def test_verify_password_survives_a_malformed_hash() -> None:
    """A corrupt or truncated stored hash must return False, not raise — otherwise a bad row
    turns a 401 into a 500 and leaks that the row exists.
    """
    assert security.verify_password("not-a-hash", "anything") is False


def test_dummy_hash_costs_the_same_as_a_real_verify() -> None:
    """The login endpoint verifies against DUMMY_PASSWORD_HASH when no user is found, so that
    an unknown email costs the same as a wrong password. If this hash were cheap — or empty —
    the timing gap would make the endpoint an account-existence oracle.

    Measured: argon2-cffi 25.1.0 at default parameters takes tens of milliseconds per verify,
    against microseconds for a malformed-hash rejection or a lookup miss — a gap of several
    orders of magnitude. The exact figure moves with machine load (three separate measurements
    on this same machine this session ranged from ~50 ms to ~100 ms), so the assertion is
    deliberately loose (>10 ms): the point is the order of magnitude, not a specific number.
    """
    real = security.hash_password("whatever")

    start = time.perf_counter()
    security.verify_password(real, "wrong")
    real_ms = (time.perf_counter() - start) * 1000

    start = time.perf_counter()
    security.verify_password(security.DUMMY_PASSWORD_HASH, "wrong")
    dummy_ms = (time.perf_counter() - start) * 1000

    assert dummy_ms > 10, f"dummy verify took {dummy_ms:.1f} ms — too cheap to hide a miss"
    assert dummy_ms > real_ms / 4, f"dummy {dummy_ms:.1f} ms vs real {real_ms:.1f} ms"


def test_access_token_round_trips() -> None:
    user_id = uuid.uuid4()

    token = security.create_access_token(user_id)

    assert security.decode_access_token(token) == user_id


def test_tampered_access_token_is_rejected() -> None:
    """Tampers with a character well inside the signature, not the last one.

    An HS256 signature is 32 bytes, encoded as 43 base64url characters — 258 bits of encoding
    for 256 bits of data, so the final character's low 2 bits are always zero-padding: its
    alphabet index must be a multiple of 4, which leaves exactly 16 legal terminal characters
    (verified: "AEIMQUYcgkosw048"), uniformly distributed. Of those 16, only 'Y' shares its top
    4 bits with 'a' — so on the 1/16 = 6.25% of tokens whose signature ends in 'Y' (a closed
    form fixed by the encoding, not a sampled estimate; three independent runs of 2000-8000
    tokens measured 6.10-6.65%, consistent with 6.25%), substituting the last character with
    'a' decodes to a byte-identical signature. The "tampered" token is then indistinguishable
    from the original, so `decode_access_token` returns the original subject instead of None
    and the assertion below fails — an intermittent, spurious red, not a false green: the bug
    was in the test's tampering, not in the code being tested. Tampering an offset well inside
    the signature instead of its low-entropy edge measured 0 such collisions over 2000 runs.
    """
    token = security.create_access_token(uuid.uuid4())
    signature_start = token.rindex(".") + 1
    offset = signature_start + 10
    tampered_char = "a" if token[offset] != "a" else "b"
    tampered = token[:offset] + tampered_char + token[offset + 1 :]

    assert security.decode_access_token(tampered) is None


def test_garbage_access_token_is_rejected() -> None:
    assert security.decode_access_token("not.a.token") is None
    assert security.decode_access_token("") is None


def test_refresh_tokens_are_unique_and_hashed() -> None:
    a = security.generate_refresh_token()
    b = security.generate_refresh_token()

    assert a != b
    # sha256 hexdigest, measured at 64 characters — which is what sizes the column.
    assert len(security.hash_refresh_token(a)) == 64
    assert security.hash_refresh_token(a) == security.hash_refresh_token(a)
    assert security.hash_refresh_token(a) != security.hash_refresh_token(b)
