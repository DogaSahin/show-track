"""allow unifiedpush transport

Revision ID: 0015
Revises: 0014
Create Date: 2026-08-30 23:44:10.000000

"""
from typing import Sequence, Union

from alembic import op

# revision identifiers, used by Alembic.
revision: str = '0015'
down_revision: Union[str, Sequence[str], None] = '0014'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # BARE "transport", NOT "ck_push_targets_transport". op.drop_constraint RE-APPLIES the
    # metadata naming convention to whatever name it is given, so the qualified form renders as
    # `ck_push_targets_ck_push_targets_transport` and the migration aborts on its first statement.
    # Measured twice already in this project — migrations 0009 (`threshold`) and 0011 (`status`)
    # both carry the same note.
    #
    # VARCHAR + CHECK rather than a native Postgres ENUM (app/db.py's enum_column), which is what
    # makes widening an enum a transactional drop-and-recreate instead of a type rewrite.
    op.drop_constraint('transport', 'push_targets', type_='check')
    op.create_check_constraint(
        'transport',
        'push_targets',
        "transport IN ('ntfy', 'unifiedpush')",
    )


def downgrade() -> None:
    """Downgrade schema."""
    # THE ONE STATEMENT HERE THAT CAN FAIL ON LIVE DATA. Narrowing a CHECK is VALIDATED against
    # existing rows, so this aborts with a bare constraint violation if any `unifiedpush` target
    # exists. Correct — the narrowed schema cannot represent them — but the remedy is manual:
    # DELETE FROM push_targets WHERE transport = 'unifiedpush' first, then re-run. Same shape as
    # 0011's `status` downgrade.
    op.drop_constraint('transport', 'push_targets', type_='check')
    op.create_check_constraint(
        'transport',
        'push_targets',
        "transport IN ('ntfy')",
    )
