from app.users.throttle import LoginThrottle, key_for

T0 = 1_000_000.0


def test_the_sixth_attempt_inside_the_window_is_refused_with_a_rounded_up_wait() -> None:
    throttle = LoginThrottle(max_attempts=5, window_seconds=900)
    for i in range(5):
        assert throttle.begin("a", T0 + i) is None
    # Oldest attempt at T0 ages out at T0 + 900; asked at T0 + 5.5 -> 894.5 s -> 895.
    assert throttle.begin("a", T0 + 5.5) == 895


def test_retry_after_is_never_zero() -> None:
    throttle = LoginThrottle(max_attempts=1, window_seconds=900)
    throttle.begin("a", T0)
    assert throttle.begin("a", T0 + 899.9) == 1


def test_attempts_age_out_of_the_window() -> None:
    throttle = LoginThrottle(max_attempts=5, window_seconds=900)
    for i in range(5):
        throttle.begin("a", T0 + i)
    assert throttle.begin("a", T0 + 905) is None


def test_reset_clears_the_key() -> None:
    throttle = LoginThrottle(max_attempts=2, window_seconds=900)
    throttle.begin("a", T0)
    throttle.begin("a", T0 + 1)
    throttle.reset("a")
    assert throttle.begin("a", T0 + 2) is None


def test_refund_removes_the_attempt() -> None:
    throttle = LoginThrottle(max_attempts=1, window_seconds=900)
    throttle.begin("a", T0)
    throttle.refund("a", T0)
    assert throttle.begin("a", T0 + 1) is None


def test_key_for_ignores_case_and_surrounding_space() -> None:
    assert key_for("  Doga@Example.COM ") == key_for("doga@example.com")


def test_a_locked_key_survives_a_full_map() -> None:
    """A spray of fresh emails must never evict a locked victim and hand back fresh guesses."""
    throttle = LoginThrottle(max_attempts=2, window_seconds=900, max_keys=10)
    throttle.begin("victim", T0)
    throttle.begin("victim", T0 + 1)
    for i in range(50):
        throttle.begin(f"junk{i}", T0 + 2 + i)
    assert throttle.begin("victim", T0 + 60) is not None


def test_expired_keys_make_room_for_new_ones() -> None:
    throttle = LoginThrottle(max_attempts=5, window_seconds=900, max_keys=2)
    throttle.begin("old1", T0)
    throttle.begin("old2", T0)
    assert throttle.begin("new", T0 + 1000) is None


def test_new_keys_are_refused_only_when_every_key_is_live() -> None:
    throttle = LoginThrottle(max_attempts=5, window_seconds=900, max_keys=2)
    throttle.begin("a", T0)
    throttle.begin("b", T0)
    assert throttle.begin("c", T0 + 1) == 900
    assert throttle.begin("a", T0 + 1) is None  # known keys still work
