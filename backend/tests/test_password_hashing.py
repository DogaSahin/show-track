import asyncio
import threading

import pytest

from app.users import security


async def _wait_for_pending(value: int) -> None:
    for _ in range(200):
        if security._pending == value:
            return
        await asyncio.sleep(0.01)
    raise AssertionError(f"_pending stuck at {security._pending}, expected {value}")


async def test_a_full_queue_is_refused_immediately(monkeypatch) -> None:
    monkeypatch.setattr(security, "ARGON2_MAX_PENDING", 0)
    with pytest.raises(security.HashingBusy):
        await security.verify_password_async(security.DUMMY_PASSWORD_HASH, "x")


async def test_a_cancelled_hash_holds_its_slot_until_the_thread_finishes(monkeypatch) -> None:
    """A disconnecting client must not free capacity while its argon2 keeps running."""
    release = threading.Event()
    monkeypatch.setattr(security, "verify_password", lambda hashed, password: release.wait(5))

    task = asyncio.create_task(security.verify_password_async("h", "p"))
    await asyncio.sleep(0.05)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert security._pending == 1
    release.set()
    await _wait_for_pending(0)


async def test_cancelling_while_queued_releases_the_pending_count(monkeypatch) -> None:
    """Cancelled before a thread started: nothing else would ever release its count."""
    release = threading.Event()
    monkeypatch.setattr(security, "verify_password", lambda hashed, password: release.wait(5))

    running = [asyncio.create_task(security.verify_password_async("h", "p")) for _ in range(2)]
    await asyncio.sleep(0.05)
    queued = asyncio.create_task(security.verify_password_async("h", "p"))
    await asyncio.sleep(0.05)
    assert security._pending == 3

    queued.cancel()
    with pytest.raises(asyncio.CancelledError):
        await queued
    assert security._pending == 2

    release.set()
    await asyncio.gather(*running)
    await _wait_for_pending(0)
