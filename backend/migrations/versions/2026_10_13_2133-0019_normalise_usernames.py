"""normalise usernames

Usernames become case-insensitively unique on their normalised form (invisible characters
removed, NFKC, trimmed), as email already is. Existing rows are normalised in Python with a frozen
copy of the app's normaliser, so both agree exactly; if that would make two accounts collide, or
leave a name blank or over 64 characters, the upgrade stops and lists them — rename first.
Collisions are checked twice: by Python's lower() and by Postgres's, which the index uses.

Revision ID: 0019
Revises: 0018
Create Date: 2026-10-13 21:33:17.380052

"""
import unicodedata
from collections import defaultdict
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0019"
down_revision: Union[str, Sequence[str], None] = "0018"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _normalise(value: str) -> str:
    # Frozen copy of app.users.schemas.normalise_username at this revision. Migrations never
    # import app code: a later change there must not rewrite what this migration did.
    visible = "".join(ch for ch in value if unicodedata.category(ch) not in ("Cf", "Cc"))
    return unicodedata.normalize("NFKC", visible).strip()


def find_problems(rows: list[tuple[object, str]]) -> list[str]:
    problems: list[str] = []
    by_folded: dict[str, list[str]] = defaultdict(list)
    for _id, name in rows:
        normalised = _normalise(name)
        if not normalised:
            problems.append(f"{name!r} is blank once normalised")
            continue
        if len(normalised) > 64:
            problems.append(f"{name!r} is longer than 64 characters once normalised")
        by_folded[normalised.lower()].append(name)
    for names in by_folded.values():
        if len(names) > 1:
            problems.append("these usernames would collide: " + ", ".join(repr(n) for n in names))
    return problems


def find_database_collisions(bind: sa.engine.Connection, rows: list[tuple[object, str]]) -> list[str]:
    """Collisions as the unique index will see them: grouped by Postgres's lower(), not Python's.

    The two can disagree (under glibc en_US.utf8, capital dotted I lowers to a plain i in Postgres
    but to i plus a combining dot in Python), and a pair only Postgres folds together would pass
    find_problems and then fail create_index with a bare UniqueViolation and no rename list.
    """
    # Listed in row order, the order find_problems uses, so a collision both checks find reads
    # identically and upgrade() can drop the duplicate.
    raw_by_normalised: dict[str, list[tuple[int, str]]] = defaultdict(list)
    for index, (_id, name) in enumerate(rows):
        normalised = _normalise(name)
        if normalised:
            raw_by_normalised[normalised].append((index, name))
    if not raw_by_normalised:
        return []
    groups = bind.execute(
        sa.text(
            "SELECT array_agg(n) FROM unnest(CAST(:names AS text[])) AS n GROUP BY lower(n) HAVING count(*) > 1"
        ),
        {"names": list(raw_by_normalised)},
    )
    problems: list[str] = []
    for (folded_together,) in groups:
        names = [raw for _index, raw in sorted(entry for n in folded_together for entry in raw_by_normalised[n])]
        problems.append("these usernames would collide: " + ", ".join(repr(n) for n in names))
    return problems


def upgrade() -> None:
    bind = op.get_bind()
    rows = [(row.id, row.username) for row in bind.execute(sa.text("SELECT id, username FROM users"))]
    problems = find_problems(rows)
    # Checked before any UPDATE, so a refusal leaves every row as it was.
    problems += [p for p in find_database_collisions(bind, rows) if p not in problems]
    if problems:
        raise RuntimeError("Rename these accounts before upgrading:\n" + "\n".join(problems))
    for user_id, name in rows:
        normalised = _normalise(name)
        if normalised != name:
            bind.execute(sa.text("UPDATE users SET username = :name WHERE id = :id"), {"name": normalised, "id": user_id})
    op.drop_constraint(op.f("uq_users_username"), "users", type_="unique")
    op.create_index("uq_users_lower_username", "users", [sa.literal_column("lower(username)")], unique=True)


def downgrade() -> None:
    # Normalised names are kept; only the uniqueness rule goes back.
    op.drop_index("uq_users_lower_username", table_name="users")
    op.create_unique_constraint(op.f("uq_users_username"), "users", ["username"])
