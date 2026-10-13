import asyncio
import threading

import pytest

from app.users import security


async def _wait_until(condition, what: str) -> None:
    """Poll rather than sleep a fixed time, so the tests don't depend on how fast this machine is.
    The 2 s ceiling only bounds a genuine failure."""
    for _ in range(200):
        if condition():
            return
        await asyncio.sleep(0.01)
    raise AssertionError(f"timed out waiting for {what}")


async def _wait_for_pending(value: int) -> None:
    await _wait_until(lambda: security._pending == value, f"_pending == {value} (is {security._pending})")


def _blocking_verify(started: list[object], release: threading.Event):
    def verify(hashed: str, password: str) -> bool:
        started.append(threading.current_thread())  # list.append is atomic under the GIL
        return release.wait(5)

    return verify


async def test_a_full_queue_is_refused_immediately(monkeypatch) -> None:
    monkeypatch.setattr(security, "ARGON2_MAX_PENDING", 0)
    with pytest.raises(security.HashingBusy):
        await security.verify_password_async(security.DUMMY_PASSWORD_HASH, "x")


async def test_a_cancelled_hash_holds_its_slot_until_the_thread_finishes(monkeypatch) -> None:
    """A disconnecting client must not free capacity while its argon2 keeps running."""
    release, started = threading.Event(), []
    monkeypatch.setattr(security, "verify_password", _blocking_verify(started, release))

    task = asyncio.create_task(security.verify_password_async("h", "p"))
    await _wait_until(lambda: len(started) == 1, "the hash thread to start")
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert security._pending == 1
    release.set()
    await _wait_for_pending(0)


async def test_cancelling_while_queued_releases_the_pending_count(monkeypatch) -> None:
    """Cancelled before a thread started: nothing else would ever release its count."""
    release, started = threading.Event(), []
    monkeypatch.setattr(security, "verify_password", _blocking_verify(started, release))

    running = [asyncio.create_task(security.verify_password_async("h", "p")) for _ in range(2)]
    await _wait_until(lambda: len(started) == 2, "both slots' threads to start")
    queued = asyncio.create_task(security.verify_password_async("h", "p"))
    await _wait_for_pending(3)
    assert len(started) == 2  # the third is queued for a slot, not running

    queued.cancel()
    with pytest.raises(asyncio.CancelledError):
        await queued
    assert security._pending == 2

    release.set()
    await asyncio.gather(*running)
    await _wait_for_pending(0)
