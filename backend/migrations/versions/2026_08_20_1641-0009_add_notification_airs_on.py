"""add notification airs_on

Revision ID: 0009
Revises: 0008
Create Date: 2026-08-20 16:41:25.896304

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0009'
down_revision: Union[str, Sequence[str], None] = '0008'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.drop_constraint(
        "uq_notification_tasks_user_id_media_id_episode_number_threshold",
        "notification_tasks",
        type_="unique",
    )
    # nullable=False with no server default is safe ONLY because notification_tasks is empty —
    # nothing has ever written it. Verified before running this.
    op.add_column(
        "notification_tasks",
        sa.Column("airs_on", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_unique_constraint(
        "uq_notification_tasks_dedup",
        "notification_tasks",
        ["user_id", "media_id", "episode_number", "threshold", "airs_on"],
    )
    # enum_column encodes the legal values in a CHECK, so renaming DAY_OF is a DDL change too.
    # Autogenerate does NOT diff CHECK bodies, so this half is hand-written.
    #
    # BARE "threshold", not "ck_notification_tasks_threshold": op.drop_constraint RE-APPLIES the
    # metadata naming convention to whatever name you pass, so the qualified form renders as
    # `ck_notification_tasks_ck_notification_tasks_threshold` and the migration aborts on its
    # first statement. Measured. This is the trap app/db.py::enum_column's own docstring already
    # documents for creates, applied to drops. The unique drops above are unaffected — the `uq`
    # convention has no %(constraint_name)s token, so those names pass through verbatim.
    op.drop_constraint("threshold", "notification_tasks", type_="check")
    op.create_check_constraint(
        "threshold", "notification_tasks", "threshold IN ('24h', 'airing_soon')"
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_constraint("threshold", "notification_tasks", type_="check")
    op.create_check_constraint("threshold", "notification_tasks", "threshold IN ('24h', 'day_of')")
    op.drop_constraint("uq_notification_tasks_dedup", "notification_tasks", type_="unique")
    op.drop_column("notification_tasks", "airs_on")
    op.create_unique_constraint(
        "uq_notification_tasks_user_id_media_id_episode_number_threshold",
        "notification_tasks",
        ["user_id", "media_id", "episode_number", "threshold"],
    )
