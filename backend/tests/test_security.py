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

    Measured: argon2-cffi 25.1.0 at default parameters takes ~50 ms per verify, against
    microseconds for a lookup miss. The assertion is deliberately loose (>10 ms) because
    absolute timings vary by machine; the point is the order of magnitude, not the number.
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
    token = security.create_access_token(uuid.uuid4())
    tampered = token[:-1] + ("a" if token[-1] != "a" else "b")

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
