import contextlib
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from fastapi.routing import iter_route_contexts
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError

from app.config import get_settings
from app.groups import service
from app.groups.dependencies import require_membership, require_ownership
from app.groups.models import Group, GroupMember, GroupRole
from app.users.models import User
from main import app
from tests.factories import make_group, make_group_member, make_user


async def _other_users_group(db_session) -> Group:
    """A group the fixture user is NOT in, owned by somebody else."""
    stranger = make_user(username="stranger", email="stranger@example.com")
    db_session.add(stranger)
    await db_session.flush()
    group = make_group(created_by=stranger.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, stranger.id, role=GroupRole.OWNER))
    await db_session.flush()
    return group


# Every route under this prefix is group-scoped and must prove membership before it answers.
_SCOPED_PREFIX = "/v1/groups/{group_id}"


def _effective_dependencies(dependant) -> set[object]:
    """Every callable reachable from a route's handler-signature dependency tree.

    Recursive because the tree is: `require_ownership` does not call `require_membership`, it
    DEPENDS on it, so on a rotate route the membership check sits one level below the
    handler's own parameter rather than beside it.
    """
    calls: set[object] = set()
    for sub in dependant.dependencies:
        calls.add(sub.call)
        calls |= _effective_dependencies(sub)
    return calls


def _scoped_route_cases() -> list[tuple[str, str, bool]]:
    """Every mounted group-scoped route as (method, path, requires_membership), DERIVED from the
    app's route table rather than restated as a list of literals.

    This is the group-scoped twin of `tests/test_auth_protection.py::_protected_cases`, and it
    exists for the same reason: a hand-written list only catches a route that omits the
    dependency if whoever added the route also remembered to append it here — which is exactly
    the memory step the check is supposed to remove. Walking `iter_route_contexts(app.routes)`
    (the same resolution FastAPI's own OpenAPI generator uses, so it sees effective,
    prefix-applied paths through fastapi 0.141.1's lazy `_IncludedRouter` wrappers) makes a new
    route covered the moment it is mounted.

    `requires_membership` is the union of two places a dependency can live, because either one
    genuinely protects the route:
      * `route_context.dependencies` — mount-level, from `include_router(..., dependencies=[...])`;
      * `route_context.dependant` — the handler's own signature, which is where `GroupMemberDep`
        and `GroupOwnerDep` sit.
    Group scoping is per-route rather than per-mount (`POST /v1/groups` and `POST
    /v1/groups/join` are on the same router and must NOT require membership), so today every
    hit comes from the second. Asserting the union states the guarantee that matters —
    "membership is checked before this handler runs" — without dictating how it is wired.
    """
    found: list[tuple[str, str, bool]] = []
    for route_context in iter_route_contexts(app.routes):
        path = route_context.path or ""
        if not path.startswith(_SCOPED_PREFIX):
            continue
        # getattr, and only after the prefix filter: a non-APIRoute context (a Mount) carries
        # neither `.methods` nor `.dependencies`, and RouteContext.__getattr__ proxies straight
        # through to an AttributeError rather than returning None.
        methods = getattr(route_context, "methods", None) or set()
        if not methods:
            continue
        mounted = {dep.dependency for dep in getattr(route_context, "dependencies", None) or []}
        signature = _effective_dependencies(route_context.dependant)
        requires_membership = require_membership in (mounted | signature)
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            found.append((method, path, requires_membership))
    return found


def _scoped_route_templates() -> list[tuple[str, str]]:
    return [(method, path) for method, path, _ in _scoped_route_cases()]


def _fill(path: str, group_id: object, user_id: object) -> str:
    return path.replace("{group_id}", str(group_id)).replace("{user_id}", str(user_id))


def test_group_scoped_routes_are_collected() -> None:
    """Guards the derivation above against silently matching nothing — a parametrized test over
    an empty list passes vacuously and would report the authorization seam as covered when it
    is not.

    Separate and non-parametrized on purpose: `_scoped_route_cases()` is also the argument to
    `@pytest.mark.parametrize`, which pytest evaluates at COLLECTION time, so an assertion
    raised inside it aborts the whole session instead of failing one named test.
    """
    templates = _scoped_route_templates()

    assert len(templates) >= 2, f"collected {len(templates)} group-scoped routes — the walk found nothing"
    assert ("GET", "/v1/groups/{group_id}/members") in templates
    assert ("POST", "/v1/groups/{group_id}/invite/rotate") in templates


@pytest.mark.parametrize(("method", "path", "requires_membership"), _scoped_route_cases())
def test_every_group_scoped_route_checks_membership(method: str, path: str, requires_membership: bool) -> None:
    """Pure route-object inspection, so it covers `{param}` routes that an HTTP-level assertion
    cannot reach without a real id.
    """
    assert requires_membership, f"{method} {path} is group-scoped but never reaches require_membership"


@pytest.mark.parametrize(("method", "path"), _scoped_route_templates())
async def test_a_non_member_cannot_tell_the_group_exists(method, path, auth_client, auth_user, db_session):
    group = await _other_users_group(db_session)

    response = await auth_client.request(method, _fill(path, group.id, auth_user.id))

    assert response.status_code == 404
    assert response.json()["detail"] == "no such group"


@pytest.mark.parametrize(("method", "path"), _scoped_route_templates())
async def test_a_nonexistent_group_answers_identically(method, path, auth_client, auth_user):
    """Same status AND same body as the non-member case — otherwise the pair is an oracle."""
    missing = "00000000-0000-4000-8000-000000000000"

    response = await auth_client.request(method, _fill(path, missing, auth_user.id))

    assert response.status_code == 404
    assert response.json()["detail"] == "no such group"


async def test_creating_a_group_makes_you_its_owner(auth_client, auth_user, db_session):
    response = await auth_client.post("/v1/groups", json={"name": "Household"})

    assert response.status_code == 201
    body = response.json()
    assert body["name"] == "Household"
    assert len(body["invite_code"]) == 12

    member = await db_session.scalar(select(GroupMember).where(GroupMember.user_id == auth_user.id))
    assert member.role is GroupRole.OWNER


async def test_the_invite_code_is_not_in_the_plain_group_representation(auth_client, auth_user):
    """GroupWithInvite is returned on create/join/rotate; GET /v1/groups must not leak a
    credential into a list every member can fetch at any time."""
    await auth_client.post("/v1/groups", json={"name": "Household"})

    listed = (await auth_client.get("/v1/groups")).json()

    assert listed[0]["name"] == "Household"
    assert "invite_code" not in listed[0]


async def test_joining_twice_is_not_an_error(auth_client, auth_user, db_session):
    """Decision 4-D's shape: POST /v1/library returns 200 for an already-tracked title rather
    than 409, and re-pasting an invite code should read the same way."""
    code = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()["invite_code"]

    again = await auth_client.post("/v1/groups/join", json={"invite_code": code})

    assert again.status_code == 200
    members = await db_session.scalars(select(GroupMember).where(GroupMember.user_id == auth_user.id))
    assert len(list(members)) == 1


async def test_a_lowercase_hyphenated_code_still_joins(auth_client, db_session):
    """The normalisation contract, end to end through HTTP rather than only in the unit test."""
    code = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()["invite_code"]
    typed = f"{code[:4]}-{code[4:8]}-{code[8:]}".lower()

    response = await auth_client.post("/v1/groups/join", json={"invite_code": typed})

    assert response.status_code == 200


async def test_joining_by_code_never_mints_an_owner(auth_client, auth_user, db_session):
    """The join path proper — a group the caller did NOT create, so `add_member` actually runs
    instead of the already-a-member early return every other join test takes.

    The role assertion is the spec invariant, not a detail: create_group's docstring says
    ownership can only come from creating the group or from G-E's transfer, "or a leaked code
    would hand over administrative control of the group rather than merely access to it".
    Nothing else in the suite fails if MEMBER becomes OWNER here.
    """
    group = await _other_users_group(db_session)

    response = await auth_client.post("/v1/groups/join", json={"invite_code": group.invite_code})

    assert response.status_code == 200
    member = await db_session.scalar(
        select(GroupMember).where(GroupMember.group_id == group.id, GroupMember.user_id == auth_user.id)
    )
    assert member is not None
    assert member.role == GroupRole.MEMBER


async def test_a_code_that_normalises_to_nothing_matches_no_group(auth_client, db_session):
    """normalise_code("---") — and ("") and ("   ") — returns the empty string. The lookup is an
    EQUALITY on that value, which no stored 12-character code can equal.

    A prefix or LIKE match would instead match EVERY group, hand `session.scalar` the first one,
    and return it to a stranger together with its invite code in GroupWithInvite. That is the
    whole reason the equality is a decision rather than a preference, and this is the only test
    that fails if it is loosened.
    """
    await _other_users_group(db_session)

    response = await auth_client.post("/v1/groups/join", json={"invite_code": "---"})

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid invite code"


async def test_a_lost_join_race_still_reports_success(auth_client, auth_user, db_session, monkeypatch):
    """The concurrent-join branch: two people paste the same code, the unique constraint picks a
    winner, and the loser is told "you are in" — true a millisecond later anyway (decision G-I).

    Two patches, because the state this asserts spans two transactions and the fixture owns one
    connection. `add_member` stands in for the losing insert: it leaves the row the WINNER would
    have committed and raises the IntegrityError Postgres would have raised. `begin_nested` is
    neutralised so that row survives the way a committed one from another transaction does —
    without that, the savepoint unwinds the stand-in along with the failure and there is no
    winner left for `join_by_code` to find. The error is constructed rather than provoked for the
    same reason: a REAL constraint violation aborts the transaction, and the row would be gone.

    What it pins is the branch, not the plumbing: given a membership row that exists once the
    failed insert has unwound, the caller gets 200 and not the generic 400. The FK twin below
    provokes the real error and covers the savepoint itself.

    The stranger seeded before the request must still be there: `session.rollback()` in this
    branch would unwind the caller's whole transaction and take that row with it — the failure
    mode Task 6 would hit, where a user created earlier in the same request vanishes and the
    endpoint still answers 200.
    """
    group = await _other_users_group(db_session)

    @contextlib.asynccontextmanager
    async def _no_savepoint():
        yield

    async def _winner_got_there_first(session_, *, group_id, user_id, role):
        session_.add(GroupMember(group_id=group_id, user_id=user_id, role=role))
        await session_.flush()
        raise IntegrityError("INSERT INTO group_members", {}, Exception("duplicate key value"))

    monkeypatch.setattr(db_session, "begin_nested", _no_savepoint)
    monkeypatch.setattr(service, "add_member", _winner_got_there_first)

    response = await auth_client.post("/v1/groups/join", json={"invite_code": group.invite_code})

    assert response.status_code == 200
    assert response.json()["name"] == group.name
    assert await db_session.scalar(select(User).where(User.username == "stranger")) is not None


async def test_an_unknown_code_is_a_generic_400(auth_client):
    response = await auth_client.post("/v1/groups/join", json={"invite_code": "ZZZZZZZZZZZZ"})

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid invite code"


async def test_rotation_invalidates_the_previous_code(auth_client, db_session):
    created = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()
    old_code, group_id = created["invite_code"], created["id"]

    rotated = await auth_client.post(f"/v1/groups/{group_id}/invite/rotate")

    assert rotated.status_code == 200
    assert rotated.json()["invite_code"] != old_code
    stale = await auth_client.post("/v1/groups/join", json={"invite_code": old_code})
    assert stale.status_code == 400


async def test_a_member_who_is_not_the_owner_cannot_rotate(auth_client, auth_user, db_session):
    """403, not 404: the caller has proven membership, so there is nothing left to hide."""
    stranger = make_user(username="stranger", email="stranger@example.com")
    db_session.add(stranger)
    await db_session.flush()
    group = make_group(created_by=stranger.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, stranger.id, role=GroupRole.OWNER),
            make_group_member(group.id, auth_user.id, role=GroupRole.MEMBER),
        ]
    )
    await db_session.flush()

    response = await auth_client.post(f"/v1/groups/{group.id}/invite/rotate")

    assert response.status_code == 403
    assert response.json()["detail"] == "only the group owner may do that"


async def test_the_owner_may_rotate_even_when_someone_else_created_the_group(auth_client, auth_user, db_session):
    """require_ownership's positive side. Ownership is the OWNER row, not `created_by`: the
    caller here did not create the group and is still admitted, which is what makes G-E's
    ownership transfer possible without rewriting `created_by`.
    """
    founder = make_user(username="founder", email="founder@example.com")
    db_session.add(founder)
    await db_session.flush()
    group = make_group(created_by=founder.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, founder.id, role=GroupRole.MEMBER),
            make_group_member(group.id, auth_user.id, role=GroupRole.OWNER),
        ]
    )
    await db_session.flush()
    old_code = group.invite_code

    response = await auth_client.post(f"/v1/groups/{group.id}/invite/rotate")

    assert response.status_code == 200
    assert response.json()["invite_code"] != old_code


async def test_ownership_compares_the_role_by_value():
    """`role` here is a bare `str` — the form SQLAlchemy binds happily for a GroupMember built
    in Python, and the form an instance can still be holding when it reaches the dependency.
    `GroupRole` is a StrEnum, so `"owner" == GroupRole.OWNER` is True while
    `"owner" is GroupRole.OWNER` is False: an identity comparison in require_ownership would
    403 a real owner.

    Called directly rather than over HTTP on purpose. Whether the ORM hands the dependency the
    `str` it was given or a `GroupRole` re-coerced by the Enum result processor turns on
    refresh timing — measured BOTH ways across near-identical setups — so an HTTP-level
    assertion would pin the operator only by luck of the draw. This one cannot.
    """
    member = make_group_member(uuid.uuid4(), uuid.uuid4(), role="owner")

    assert await require_ownership(member) is member


async def test_an_expired_code_is_indistinguishable_from_a_wrong_one(auth_client, db_session):
    created = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()
    group = await db_session.get(Group, uuid.UUID(created["id"]))
    group.invite_code_expires_at = datetime(2000, 1, 1, tzinfo=UTC)
    await db_session.flush()

    response = await auth_client.post("/v1/groups/join", json={"invite_code": created["invite_code"]})

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid invite code"


async def test_the_member_list_names_everyone_in_the_group(auth_client, auth_user, db_session):
    """The one read path a plain member has, ordered by joined_at ASC.

    `joined_at` is set explicitly here rather than left to its server default, because
    Postgres' now() is TRANSACTION start time: two rows inserted in one transaction get the
    identical timestamp and the order falls through to the `id` tiebreak, which is a random
    UUID rather than insertion order. Leaving it defaulted made this assertion pass or fail on
    the UUID draw.
    """
    created = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()
    housemate = make_user(username="housemate", email="housemate@example.com")
    db_session.add(housemate)
    await db_session.flush()
    db_session.add(
        make_group_member(
            uuid.UUID(created["id"]),
            housemate.id,
            role=GroupRole.MEMBER,
            joined_at=datetime(2030, 1, 1, tzinfo=UTC),
        )
    )
    await db_session.flush()

    response = await auth_client.get(f"/v1/groups/{created['id']}/members")

    assert response.status_code == 200
    body = response.json()
    assert {row["username"] for row in body} == {"fixture-user", "housemate"}
    assert [row["username"] for row in body] == ["fixture-user", "housemate"]
    assert body[0]["role"] == "owner"


async def test_listing_groups_shows_only_your_own(auth_client, auth_user, db_session):
    await _other_users_group(db_session)
    await auth_client.post("/v1/groups", json={"name": "Household"})

    listed = (await auth_client.get("/v1/groups")).json()

    assert [row["name"] for row in listed] == ["Household"]


async def test_a_group_deleted_mid_join_is_a_400_not_a_200(auth_client, auth_user, db_session, monkeypatch):
    """The other half of the `except IntegrityError` branch above, and the one that used to lie.

    `add_member` is patched to delete the group and THEN insert the membership, so Postgres
    raises a real foreign-key violation — the same error the production race produces when the
    last member's departure commits while this join is blocked on the `groups` row. The old
    blanket handler could not tell that apart from a lost unique-constraint race, so it returned
    the group and the route answered 200 with GroupWithInvite: a group id and a live invite code
    for a group that no longer exists.

    Generic 400, and no membership row: the join did not happen. That the re-select can run at
    all is the savepoint doing its job — a real IntegrityError aborts the whole Postgres
    transaction, so without `begin_nested` the next statement would fail too and the route would
    500 rather than answer.
    """
    group = await _other_users_group(db_session)

    async def _group_vanishes(session_, *, group_id, user_id, role):
        await session_.execute(delete(Group).where(Group.id == group_id))
        session_.add(GroupMember(group_id=group_id, user_id=user_id, role=role))
        await session_.flush()  # violates group_members.group_id -> groups.id

    monkeypatch.setattr(service, "add_member", _group_vanishes)

    response = await auth_client.post("/v1/groups/join", json={"invite_code": group.invite_code})

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid invite code"
    assert await db_session.scalar(select(User).where(User.username == "stranger")) is not None
    assert (
        await db_session.scalar(
            select(GroupMember.id).where(GroupMember.group_id == group.id, GroupMember.user_id == auth_user.id)
        )
        is None
    )


async def test_rotating_a_group_deleted_mid_request_is_a_404(auth_client, auth_user, db_session, monkeypatch):
    """require_ownership resolves against a group that is deleted before the handler loads it.

    Patched rather than raced because the test fixture runs the request on the same single
    connection as the test body, so there is no second transaction to commit the deletion from.
    The patch is narrow — only `Group` vanishes, so `get_current_user`'s own `session.get(User)`
    still resolves — and it reproduces exactly what the handler sees: a SELECT that returns no
    row. Unchecked, the next line is `group.invite_code = ...` on None: a 500.
    """
    created = (await auth_client.post("/v1/groups", json={"name": "Household"})).json()
    real_get = db_session.get

    async def _group_vanished(entity, ident, *args, **kwargs):
        if entity is Group:
            return None
        return await real_get(entity, ident, *args, **kwargs)

    monkeypatch.setattr(db_session, "get", _group_vanished)

    response = await auth_client.post(f"/v1/groups/{created['id']}/invite/rotate")

    assert response.status_code == 404
    assert response.json()["detail"] == "no such group"


async def test_the_invite_expiry_is_the_configured_ttl_in_hours(db_session, auth_user, monkeypatch):
    """Nothing else reads `invite_code_expires_at` back: the two expiry tests hard-set the year
    2000, so they pass whatever `_expiry` computes. Changing `hours=` to `days=` kept the whole
    suite green while turning a one-week window into a 24-week one.

    The injected TTL is deliberately neither the default nor a value where the units coincide, so
    the assertion fails for a wrong unit AND for a hardcoded 168 — comparing against the live
    setting alone cannot tell those apart while the setting IS 168. Same monkeypatch shape as
    tests/test_scheduler.py; `_expiry` is the only reader, and it wants one attribute.

    Decision G-B makes this window a security control — an invite code creates an account and
    lives forever in someone's chat history — so the deployed magnitude is bounded too. That
    bound is loose on purpose: it is not asserting today's value, it is asserting the setting
    cannot drift into "effectively never expires" unnoticed.

    Rotation is asserted because it issues a fresh expiry through the same `_expiry`, and it is
    the revocation mechanism: a rotation that did not move the window forward would leave the new
    code expiring on the old code's schedule.
    """
    assert timedelta(hours=get_settings().group_invite_ttl_hours) <= timedelta(days=30)

    monkeypatch.setattr(service, "get_settings", lambda: SimpleNamespace(group_invite_ttl_hours=5))
    created_at = datetime(2026, 1, 1, tzinfo=UTC)
    rotated_at = datetime(2026, 3, 1, tzinfo=UTC)

    group = await service.create_group(db_session, name="Household", owner=auth_user, now=created_at)
    assert group.invite_code_expires_at == created_at + timedelta(hours=5)

    await service.rotate_invite_code(db_session, group=group, now=rotated_at)
    assert group.invite_code_expires_at == rotated_at + timedelta(hours=5)
