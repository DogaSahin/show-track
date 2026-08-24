import pytest

from app.groups.models import Group, GroupRole
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


# Every group-scoped route, so adding one without the dependency fails here.
def _scoped_routes(group_id, user_id):
    return [
        ("GET", f"/v1/groups/{group_id}/members", None),
        ("DELETE", f"/v1/groups/{group_id}/members/{user_id}", None),
        ("POST", f"/v1/groups/{group_id}/invite/rotate", None),
    ]


@pytest.mark.parametrize("index", [0, 1, 2])
async def test_a_non_member_cannot_tell_the_group_exists(index, auth_client, auth_user, db_session):
    group = await _other_users_group(db_session)
    method, path, body = _scoped_routes(group.id, auth_user.id)[index]

    response = await auth_client.request(method, path, json=body)

    assert response.status_code == 404
    assert response.json()["detail"] == "no such group"


@pytest.mark.parametrize("index", [0, 1, 2])
async def test_a_nonexistent_group_answers_identically(index, auth_client, auth_user):
    """Same status AND same body as the non-member case — otherwise the pair is an oracle."""
    missing = "00000000-0000-4000-8000-000000000000"
    method, path, body = _scoped_routes(missing, auth_user.id)[index]

    response = await auth_client.request(method, path, json=body)

    assert response.status_code == 404
    assert response.json()["detail"] == "no such group"
